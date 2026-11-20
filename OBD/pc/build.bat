@echo off
setlocal
pushd "%~dp0"
if errorlevel 1 exit /b 1
python -m pip install pyinstaller
if errorlevel 1 goto failed
python -c "from datetime import datetime, timezone; from pathlib import Path; Path('build').mkdir(exist_ok=True); Path('build/build_date.txt').write_text(datetime.now(timezone.utc).strftime('%%Y%%m%%d'), encoding='ascii')"
if errorlevel 1 goto failed
python -m PyInstaller --noconfirm --clean --onefile --console --name MercedesDashboard --icon ..\mercedesobd.ico --distpath . --add-data "build\build_date.txt;." mercedes_dashboard.py
if errorlevel 1 goto failed
move /Y MercedesDashboard.exe ..\MercedesDashboard.exe
if errorlevel 1 goto failed
popd
exit /b 0

:failed
popd
exit /b 1
