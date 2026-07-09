# Deploy UpDesk on AWS (first-timer walkthrough)

Goal: one EC2 server that lets host + controller connect from **any network**
(different WiFi, different cities). ~$15–20/mo — covered by your credits.

## 1. Launch the server (AWS Console)
1. console.aws.amazon.com → pick a **Region** near you (e.g. Mumbai `ap-south-1`).
2. Search **EC2** → **Launch instance**.
   - Name: `updesk-server`
   - Image: **Ubuntu Server 24.04 LTS**
   - Type: **t3.small**
   - Key pair: **Create new** → `updesk-key` → download `updesk-key.pem`
   - Network → **Edit** → add these inbound rules:
     | Type | Port | Source |
     |------|------|--------|
     | SSH | 22 | My IP |
     | HTTPS | 443 | Anywhere |
     | Custom TCP | 3478 | Anywhere |
     | Custom UDP | 3478 | Anywhere |
     | Custom UDP | 49152–65535 | Anywhere |
   - **Launch instance**
3. EC2 left menu → **Elastic IPs** → **Allocate** → then **Actions → Associate**
   → choose `updesk-server`. **Copy this IP.**

## 2. Free domain
duckdns.org → sign in → make `yourname.duckdns.org` → set it to the Elastic IP.

## 3. Connect to the server
In PowerShell, in the folder with `updesk-key.pem`:
```powershell
icacls updesk-key.pem /inheritance:r /grant:r "$($env:USERNAME):(R)"   # fix key perms (once)
ssh -i updesk-key.pem ubuntu@YOUR-ELASTIC-IP
```
(Type `yes` at the first prompt.)

## 4. Run the install script
On the server:
```bash
git clone <your-repo> updesk && cd updesk/infra
nano aws-setup.sh          # set DOMAIN, TURN_SECRET, REPO at the top, save (Ctrl+O, Enter, Ctrl+X)
bash aws-setup.sh
```
It installs + starts the signaling server, Caddy (auto HTTPS cert), and coturn.
First run takes a few minutes (it builds the server). When it finishes it prints
your `wss://…` and TURN details.

## 5. Verify
From your own PC:
```powershell
Test-NetConnection yourname.duckdns.org -Port 443    # TcpTestSucceeded = True
```
Open `https://yourname.duckdns.org` in a browser — a valid padlock means the cert
worked.

## 6. Point the apps at it
Tell me the domain + TURN secret and I'll set the apps' defaults + TURN creds and
rebuild both installers. After that, host and controller connect via
`wss://yourname.duckdns.org` from **anywhere** — no same-WiFi, no IP typing.

---
### Managing the server later
```bash
sudo systemctl status updesk-signaling caddy coturn   # health
sudo journalctl -u updesk-signaling -f                # live logs
```
