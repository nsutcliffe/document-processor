import requests
import json
from typing import Optional, Dict, Any

class ApiClient:
    def __init__(self, base_url: str = "http://localhost:8080"):
        self.base_url = base_url
    
    def upload_file(self, file_bytes: bytes, filename: str) -> Dict[str, Any]:
        """Upload a file and get processing results."""
        url = f"{self.base_url}/api/files/upload"
        files = {'file': (filename, file_bytes)}
        
        try:
            response = requests.post(url, files=files, timeout=60)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as e:
            return {
                "error": True,
                "message": f"Upload failed: {str(e)}",
                "fileId": None
            }
    
    def get_file_result(self, file_id: str) -> Optional[Dict[str, Any]]:
        """Get processing results for a file."""
        url = f"{self.base_url}/api/files/{file_id}"
        
        try:
            response = requests.get(url, timeout=30)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException:
            return None
    
    def download_file(self, file_id: str) -> Optional[bytes]:
        """Download the original file."""
        url = f"{self.base_url}/api/files/{file_id}/download"
        
        try:
            response = requests.get(url, timeout=30)
            response.raise_for_status()
            return response.content
        except requests.exceptions.RequestException:
            return None

