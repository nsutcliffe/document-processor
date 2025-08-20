#!/usr/bin/env python3
"""
Setup script for Tunic Pay Document Processor Frontend
"""

import subprocess
import sys
import os
import venv

def create_venv():
    """Create virtual environment if it doesn't exist."""
    venv_dir = "venv"
    if not os.path.exists(venv_dir):
        print("🔨 Creating virtual environment...")
        venv.create(venv_dir, with_pip=True)
        print("✅ Virtual environment created!")
    else:
        print("✅ Virtual environment already exists!")
    return venv_dir

def get_venv_python(venv_dir):
    """Get the path to Python in the virtual environment."""
    if sys.platform == "win32":
        return os.path.join(venv_dir, "Scripts", "python.exe")
    else:
        return os.path.join(venv_dir, "bin", "python")

def install_requirements(python_path):
    """Install Python requirements in virtual environment."""
    print("📦 Installing Python dependencies in virtual environment...")
    subprocess.check_call([python_path, "-m", "pip", "install", "--upgrade", "pip"])
    subprocess.check_call([python_path, "-m", "pip", "install", "-r", "requirements.txt"])
    print("✅ Dependencies installed!")

def run_app(python_path):
    """Run the Streamlit app."""
    print("🚀 Starting Streamlit app...")
    print("📱 App will be available at: http://localhost:8501")
    print("🔗 Make sure the backend is running at: http://localhost:8080")
    print("\n" + "="*50)
    
    subprocess.run([python_path, "-m", "streamlit", "run", "app.py"])

if __name__ == "__main__":
    print("🏗️  Tunic Pay Document Processor Setup")
    print("="*50)
    
    # Change to frontend directory
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    
    try:
        # Create virtual environment
        venv_dir = create_venv()
        python_path = get_venv_python(venv_dir)
        
        # Install dependencies
        install_requirements(python_path)
        
        print("\n" + "="*50)
        
        # Run the app
        run_app(python_path)
        
    except KeyboardInterrupt:
        print("\n👋 Setup cancelled by user")
    except Exception as e:
        print(f"\n❌ Error: {e}")
        sys.exit(1)
