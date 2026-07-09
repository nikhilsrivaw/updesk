// Windows service wrapper for the silent native host.
//
// Installed as a LocalSystem auto-start service. At boot it launches the native
// host (streaming mode, no args) in the ACTIVE console session — so it captures
// the logged-in user's desktop unattended, surviving reboots and session
// switches. (Capturing the login/UAC secure desktop is a further step.)
//
//   native-host install     register + auto-start at boot (run elevated)
//   native-host uninstall    stop + remove
//   native-host service      SCM entry point (not for manual use)

use std::error::Error;
use std::ffi::{c_void, OsString};
use std::sync::mpsc;
use std::time::Duration;

use windows::core::{PCWSTR, PWSTR};
use windows::Win32::Foundation::{CloseHandle, HANDLE, WAIT_OBJECT_0};
use windows::Win32::Security::{
    DuplicateTokenEx, SecurityIdentification, TokenPrimary, TOKEN_ALL_ACCESS,
};
use windows::Win32::System::Environment::{CreateEnvironmentBlock, DestroyEnvironmentBlock};
use windows::Win32::System::RemoteDesktop::{WTSGetActiveConsoleSessionId, WTSQueryUserToken};
use windows::Win32::System::Threading::{
    CreateProcessAsUserW, TerminateProcess, WaitForSingleObject, CREATE_NEW_CONSOLE,
    CREATE_UNICODE_ENVIRONMENT, PROCESS_INFORMATION, STARTUPINFOW,
};
use windows_service::service::{
    ServiceAccess, ServiceControl, ServiceControlAccept, ServiceErrorControl, ServiceExitCode,
    ServiceInfo, ServiceStartType, ServiceState, ServiceStatus, ServiceType,
};
use windows_service::service_control_handler::{self, ServiceControlHandlerResult};
use windows_service::service_manager::{ServiceManager, ServiceManagerAccess};
use windows_service::{define_windows_service, service_dispatcher};

const SERVICE_NAME: &str = "UpDeskNativeHost";
const DISPLAY_NAME: &str = "UpDesk Native Host";
const HOST_EXE: &str = "native-host.exe";
const NO_SESSION: u32 = 0xFFFF_FFFF;

type BoxErr = Box<dyn Error>;

pub fn install() -> anyhow::Result<()> {
    do_install().map_err(|e| anyhow::anyhow!(e.to_string()))
}
pub fn uninstall() -> anyhow::Result<()> {
    do_uninstall().map_err(|e| anyhow::anyhow!(e.to_string()))
}
pub fn run_dispatcher() -> anyhow::Result<()> {
    service_dispatcher::start(SERVICE_NAME, ffi_service_main).map_err(|e| anyhow::anyhow!(e.to_string()))?;
    Ok(())
}

fn do_install() -> Result<(), BoxErr> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CREATE_SERVICE)?;
    let info = ServiceInfo {
        name: OsString::from(SERVICE_NAME),
        display_name: OsString::from(DISPLAY_NAME),
        service_type: ServiceType::OWN_PROCESS,
        start_type: ServiceStartType::AutoStart,
        error_control: ServiceErrorControl::Normal,
        executable_path: std::env::current_exe()?,
        launch_arguments: vec![OsString::from("service")],
        dependencies: vec![],
        account_name: None, // LocalSystem
        account_password: None,
    };
    let service = manager.create_service(&info, ServiceAccess::CHANGE_CONFIG | ServiceAccess::START)?;
    let _ = service.set_description("UpDesk silent native host (unattended, from boot).");
    println!("Installed '{SERVICE_NAME}'. Start it with:  sc start {SERVICE_NAME}");
    Ok(())
}

fn do_uninstall() -> Result<(), BoxErr> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)?;
    let access = ServiceAccess::STOP | ServiceAccess::DELETE | ServiceAccess::QUERY_STATUS;
    let service = manager.open_service(SERVICE_NAME, access)?;
    let _ = service.stop();
    service.delete()?;
    println!("Uninstalled '{SERVICE_NAME}'.");
    Ok(())
}

define_windows_service!(ffi_service_main, service_main);

fn service_main(_args: Vec<OsString>) {
    let _ = run_service();
}

fn run_service() -> Result<(), BoxErr> {
    let (shutdown_tx, shutdown_rx) = mpsc::channel::<()>();
    let handler = move |control| -> ServiceControlHandlerResult {
        match control {
            ServiceControl::Stop | ServiceControl::Shutdown => {
                let _ = shutdown_tx.send(());
                ServiceControlHandlerResult::NoError
            }
            ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
            _ => ServiceControlHandlerResult::NotImplemented,
        }
    };
    let status_handle = service_control_handler::register(SERVICE_NAME, handler)?;
    let running = ServiceStatus {
        service_type: ServiceType::OWN_PROCESS,
        current_state: ServiceState::Running,
        controls_accepted: ServiceControlAccept::STOP | ServiceControlAccept::SHUTDOWN,
        exit_code: ServiceExitCode::Win32(0),
        checkpoint: 0,
        wait_hint: Duration::default(),
        process_id: None,
    };
    status_handle.set_service_status(running.clone())?;

    let mut child: Option<(u32, Child)> = None;
    loop {
        supervise(&mut child);
        if shutdown_rx.recv_timeout(Duration::from_secs(3)).is_ok() {
            break;
        }
    }
    if let Some((_, c)) = child.take() {
        c.terminate();
    }
    status_handle.set_service_status(ServiceStatus { current_state: ServiceState::Stopped, ..running })?;
    Ok(())
}

fn supervise(child: &mut Option<(u32, Child)>) {
    let session = unsafe { WTSGetActiveConsoleSessionId() };
    if session == NO_SESSION {
        return;
    }
    let needs_launch = match child {
        Some((sess, c)) => *sess != session || !c.is_alive(),
        None => true,
    };
    if !needs_launch {
        return;
    }
    if let Some((_, old)) = child.take() {
        old.terminate();
    }
    if let Some(c) = launch_in_session(session) {
        *child = Some((session, c));
    }
}

struct Child {
    process: HANDLE,
    thread: HANDLE,
}
impl Child {
    fn is_alive(&self) -> bool {
        unsafe { WaitForSingleObject(self.process, 0) != WAIT_OBJECT_0 }
    }
    fn terminate(&self) {
        unsafe {
            let _ = TerminateProcess(self.process, 1);
            let _ = CloseHandle(self.process);
            let _ = CloseHandle(self.thread);
        }
    }
}

fn exe_path() -> Option<String> {
    let dir = std::env::current_exe().ok()?.parent()?.to_path_buf();
    Some(dir.join(HOST_EXE).to_string_lossy().into_owned())
}
fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

fn launch_in_session(session: u32) -> Option<Child> {
    let exe = exe_path()?;
    unsafe {
        let mut user_token = HANDLE::default();
        if WTSQueryUserToken(session, &mut user_token).is_err() {
            return None;
        }
        let mut primary = HANDLE::default();
        let dup = DuplicateTokenEx(
            user_token, TOKEN_ALL_ACCESS, None, SecurityIdentification, TokenPrimary, &mut primary,
        );
        let _ = CloseHandle(user_token);
        if dup.is_err() {
            return None;
        }
        let mut env: *mut c_void = std::ptr::null_mut();
        let have_env = CreateEnvironmentBlock(&mut env, primary, false).is_ok();

        let mut app = wide(&exe);
        let mut cmd = wide(&format!("\"{exe}\"")); // no args -> streaming mode
        let mut desktop = wide("winsta0\\default");
        let mut si = STARTUPINFOW {
            cb: std::mem::size_of::<STARTUPINFOW>() as u32,
            lpDesktop: PWSTR(desktop.as_mut_ptr()),
            ..Default::default()
        };
        let mut pi = PROCESS_INFORMATION::default();

        let ok = CreateProcessAsUserW(
            primary,
            PCWSTR(app.as_mut_ptr()),
            PWSTR(cmd.as_mut_ptr()),
            None,
            None,
            false,
            CREATE_UNICODE_ENVIRONMENT | CREATE_NEW_CONSOLE,
            if have_env { Some(env) } else { None },
            PCWSTR::null(),
            &si,
            &mut pi,
        );
        if have_env {
            let _ = DestroyEnvironmentBlock(env);
        }
        let _ = CloseHandle(primary);
        let _ = (&mut app, &mut cmd, &mut si);
        if ok.is_err() {
            return None;
        }
        Some(Child { process: pi.hProcess, thread: pi.hThread })
    }
}
