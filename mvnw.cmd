@ECHO OFF
SETLOCAL
SET BASE_DIR=%~dp0
SET WRAPPER_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.12
SET MAVEN_HOME=%WRAPPER_HOME%\apache-maven-3.9.12
IF NOT EXIST "%MAVEN_HOME%\bin\mvn.cmd" (
  IF NOT EXIST "%WRAPPER_HOME%" MKDIR "%WRAPPER_HOME%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.12/apache-maven-3.9.12-bin.zip' -OutFile '%WRAPPER_HOME%\maven.zip'; Expand-Archive -Force '%WRAPPER_HOME%\maven.zip' '%WRAPPER_HOME%'"
  IF ERRORLEVEL 1 EXIT /B 1
)
CALL "%MAVEN_HOME%\bin\mvn.cmd" %*
ENDLOCAL
