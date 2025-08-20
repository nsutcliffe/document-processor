@echo off
echo 🧪 Tunic Pay Frontend - Test Suite
echo ===================================
echo.

REM Check if virtual environment exists
if not exist "venv" (
    echo 🔨 Creating virtual environment...
    python -m venv venv
)

echo 🔧 Activating virtual environment...
call venv\Scripts\activate.bat

echo 📦 Installing test dependencies...
pip install -q unittest-xml-reporting

echo.
echo 🧪 Running Python unit tests...
python -m unittest discover -s . -p "test_*.py" -v

if %ERRORLEVEL% neq 0 (
    echo ❌ Some tests failed!
    pause
    exit /b 1
)

echo.
echo ✅ All frontend tests passed!
echo.
echo 📊 Test Coverage:
echo    API Client: Network handling, error cases, success flows
echo    Error Handling: Connection errors, server errors, timeouts
echo    Configuration: Base URL handling, parameter validation
echo.

pause
