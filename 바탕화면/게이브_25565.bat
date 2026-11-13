@echo off
title 게이브 25565
cd /d "%USERPROFILE%\Desktop\클로드\gave\live" || (echo 서버 폴더를 찾지 못했습니다. & pause & exit /b 1)
set "JAVA=%USERPROFILE%\.jdks\temurin-25\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"
netstat -ano | findstr /r /c:":25565 .*LISTENING" >nul && (echo 25565 포트를 이미 다른 프로그램이 쓰고 있습니다. 다른 서버를 먼저 끄세요. & pause & exit /b 1)
if exist "..\build\Gave.jar" copy /y "..\build\Gave.jar" "plugins\Gave.jar" >nul
echo 게이브 서버를 25565 포트로 켭니다. 끌 때는 이 창에 stop 을 입력하세요.
echo 리소스팩 두 개(모습, 소리)는 깃허브에서 받고, 거기 없는 팩은 이 PC 의 8088 포트로 보냅니다.
echo 다른 PC 에서 들어오려면 공유기에서 25565 포트를, 팩을 이 PC 에서 보낼 때는 8088 포트도 여세요.
echo 처음 켤 때는 광장을 짓느라 조금 걸립니다. 판 시작은 게임 안에서 /gave 시작
echo.
"%JAVA%" -Xms2G -Xmx4G -Dfile.encoding=UTF-8 -Dspark.backgroundProfiler=false -Dgave.packPort=8088 -Dgave.packRepo=tinymita2-pixel/gave-pack -jar paper.jar --nogui
echo.
echo 서버가 꺼졌습니다.
pause
