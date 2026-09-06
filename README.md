# PaulaDeployer

Phone-friendly field webapp for flashing Daffodil firmware from a Raspberry Pi ("Paula") - Inspect,
Send Command, Calibrate CSW, and a tile-per-device deploy queue with a live flash terminal.

This is the webapp half of the Paula field toolkit. The Pi it runs on, the CLI tool it shares a
Postgres database and serial-link code with, and the one-shot provisioning script that sets up
Tomcat/Postgres/the esptool toolchain/WiFi for both of them all live in
**[PaulaUploader](https://github.com/arifainchtein/PaulaUploader)** - start there for a fresh Pi.

## Building and deploying

```bash
mvn package
```

Packages a `ROOT.war` and (via the `maven-antrun-plugin` in `pom.xml`) scp's it straight into
`~/pauladeployer/tomcat/webapps/ROOT.war` on the target Pi - override the target with
`-Dserver.address=<hostname>` if it's not the pom's default.

After a redeploy, restart Tomcat via its systemd service rather than relying on hot-redeploy -
this app loads jSerialComm's native library, which can only be loaded once per JVM process, so a
plain WAR swap without a real process restart will crash on the next serial call:

```bash
ssh pi@<paula-hostname> 'sudo systemctl restart pauladeployer-tomcat'
```
