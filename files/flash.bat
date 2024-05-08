@echo off&setlocal enabledelayedexpansion
title  chuest

echo.  Y=升级-保数据刷入           N=首次-格式化用户数据
echo.
echo.
set /p CHOICE=您的选择：
cd %~dp0

fastboot flash boot images/boot.img 
fastboot flash init_boot images/init_boot.img

fastboot set_active a   >NUL 2>NUL

fastboot erase super  >NUL 2>NUL
ping -n 5 127.0.0.1 >nul 2>nul
fastboot flash super images/super.img

@REM fastboot flash cust images/cust.img

for /f %%i in ('dir /b images ^| findstr /v /i "boot.img" ^| findstr /v /i "boot.img" ^| findstr /v /i "cust.img" ^| findstr /v /i "super.img"') do ( 
	set par=%%~ni
	set url=images\%%i
	fastboot flash !par!_a !url!
	fastboot flash !par!_b !url!
)


if /I "%CHOICE%" == "N" (
	echo.用户数据正在清除中...
	fastboot %* erase userdata
	fastboot %* erase metadata
	fastboot %* erase secdata
	fastboot %* erase exaid
	echo.
)
echo.
echo.
echo.  恭喜您刷机完成，系统正在重启，如无响应可手动重启
echo.
fastboot oem cdms  >NUL 2>NUL
fastboot set_active a  >NUL 2>NUL
fastboot reboot 

echo.  按任意键关闭此窗口
echo.
pause
exit