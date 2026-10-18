@rem testigo: el runner, Maestro y bundletool necesitan Java 17 o mas. Se lee de JAVA_HOME\release; sin JAVA_HOME no se revisa.
set TESTIGO_JAVA_VERSION=
set TESTIGO_JAVA_MAJOR=
if defined JAVA_HOME if exist "%JAVA_HOME%\release" for /f "usebackq tokens=1,2 delims==" %%a in ("%JAVA_HOME%\release") do if "%%a"=="JAVA_VERSION" set TESTIGO_JAVA_VERSION=%%~b
if defined TESTIGO_JAVA_VERSION for /f "tokens=1,2 delims=." %%a in ("%TESTIGO_JAVA_VERSION%") do if "%%a"=="1" (set TESTIGO_JAVA_MAJOR=%%b) else (set TESTIGO_JAVA_MAJOR=%%a)
if defined TESTIGO_JAVA_MAJOR if %TESTIGO_JAVA_MAJOR% LSS 17 goto testigoOldJava
goto testigoJavaOk

:testigoOldJava
echo. 1>&2
echo Error: testigo necesita Java 17 o mas, y JAVA_HOME apunta a Java %TESTIGO_JAVA_VERSION%: %JAVA_HOME% 1>&2
echo Apunta JAVA_HOME a un JDK 17 o mas nuevo, por ejemplo el que trae Android Studio (jbr), y vuelve a intentarlo. 1>&2
echo. 1>&2
exit /b 2

:testigoJavaOk
