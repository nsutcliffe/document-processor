@echo off
echo 🏗️  Tunic Pay Document Processor Frontend
echo ==================================================

cd /d "%~dp0"

echo 🔨 Setting up virtual environment...
if not exist "venv" (
    python -m venv venv
    echo ✅ Virtual environment created!
) else (
    echo ✅ Virtual environment already exists!
)

echo 📦 Installing dependencies...
venv\Scripts\python.exe -m pip install --upgrade pip
venv\Scripts\python.exe -m pip install -r requirements.txt

echo.
echo ==================================================
echo 🚀 Starting Streamlit app...
echo 📱 App will be available at: http://localhost:8501
echo 🔗 Make sure the backend is running at: http://localhost:8080
echo ==================================================
echo.

venv\Scripts\python.exe -m streamlit run app.py

pause

