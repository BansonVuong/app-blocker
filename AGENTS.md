Always run tests after modifying any files, but only after modifying files.

If you grab UI dumps, make sure to name them appropriately.

If Gradle debug resource builds fail with a persistent lock on `app/build/.../R.jar`, run this recovery sequence:
1. `.\gradlew.bat --stop`
2. `Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object { $_.CommandLine -like "*vscode-gradle*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }`
3. `Remove-Item -Recurse -Force .\app\build`
4. `.\gradlew.bat test --no-daemon`

Keep workspace Gradle import disabled (`java.import.gradle.enabled=false`) and exclude `**/build/**` from watchers/search to reduce lock recurrence.
