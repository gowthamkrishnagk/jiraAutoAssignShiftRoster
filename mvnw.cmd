@REM ----------------------------------------------------------------------------
@REM Maven Start Up Batch script
@REM ----------------------------------------------------------------------------
@echo off
set MAVEN_PROJECTBASEDIR=%~dp0

set MAVEN_WRAPPER_JAR="%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar"
set MAVEN_WRAPPER_PROPERTIES="%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.properties"

FOR /F "usebackq tokens=1,2 delims==" %%A IN (%MAVEN_WRAPPER_PROPERTIES%) DO (
    IF "%%A"=="distributionUrl" SET DISTRIBUTION_URL=%%B
    IF "%%A"=="wrapperUrl" SET WRAPPER_URL=%%B
)

SET MVNW_REPOURL=https://repo.maven.apache.org/maven2

IF NOT EXIST %MAVEN_WRAPPER_JAR% (
    echo Downloading maven-wrapper.jar...
    powershell -Command "Invoke-WebRequest -Uri '%WRAPPER_URL%' -OutFile '%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar'"
)

%JAVA_HOME%\bin\java.exe -jar %MAVEN_WRAPPER_JAR% %*
if ERRORLEVEL 1 goto error
goto end

:error
set ERROR_CODE=%ERRORLEVEL%

:end
exit /B %ERROR_CODE%
