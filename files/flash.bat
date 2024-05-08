@echo off&setlocal enabledelayedexpansion
title  chuest

echo.  Y=����-������ˢ��           N=�״�-��ʽ���û�����
echo.
echo.
set /p CHOICE=����ѡ��
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
	echo.�û��������������...
	fastboot %* erase userdata
	fastboot %* erase metadata
	fastboot %* erase secdata
	fastboot %* erase exaid
	echo.
)
echo.
echo.
echo.  ��ϲ��ˢ����ɣ�ϵͳ����������������Ӧ���ֶ�����
echo.
fastboot oem cdms  >NUL 2>NUL
fastboot set_active a  >NUL 2>NUL
fastboot reboot 

echo.  ��������رմ˴���
echo.
pause
exit