class DeviceRegistry {
  constructor() {
    this.devices = new Map(); // deviceId -> { ws, lastSeen, metadata }
  }

  register(deviceId, ws, metadata = {}) {
    this.devices.set(deviceId, {
      ws,
      lastSeen: Date.now(),
      metadata
    });
    console.log(`Device registered: ${deviceId}`);
  }

  unregister(deviceId) {
    this.devices.delete(deviceId);
    console.log(`Device unregistered: ${deviceId}`);
  }

  get(deviceId) {
    return this.devices.get(deviceId);
  }

  isOnline(deviceId) {
    return this.devices.has(deviceId);
  }

  updateLastSeen(deviceId) {
    const device = this.devices.get(deviceId);
    if (device) device.lastSeen = Date.now();
  }

  listOnline() {
    return Array.from(this.devices.keys());
  }
}

module.exports = new DeviceRegistry();
