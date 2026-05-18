# Testing

## Join a match via deep link (P2 side)

Fires the `midnight://kicks?match=<address>` intent at a specific
emulator. Replace `<address>` with the 64-char hex contract address
shown on P1's CreateMatchScreen, and `<serial>` with the joiner's adb
serial (e.g. `emulator-5556` — `adb devices -l` to confirm).

```bash
adb -s <serial> shell am start \
    -a android.intent.action.VIEW \
    -d "midnight://kicks?match=<address>"
```
