python -m pip install pyinstaller
python -m PyInstaller --noconfirm --clean --onefile --console --name MercedesDashboard --icon "mercedesobd.ico" --distpath . "mercedes_dashboard.py"