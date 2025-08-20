import unittest
from unittest.mock import patch, Mock
import requests
from services.api_client import ApiClient

class TestApiClient(unittest.TestCase):
    
    def setUp(self):
        self.client = ApiClient("http://localhost:8080")
    
    @patch('services.api_client.requests.post')
    def test_upload_file_success(self, mock_post):
        # Mock successful response
        mock_response = Mock()
        mock_response.json.return_value = {
            "fileId": "test-id",
            "category": "invoice",
            "confidenceScore": 0.95
        }
        mock_response.raise_for_status.return_value = None
        mock_post.return_value = mock_response
        
        result = self.client.upload_file(b"test content", "test.txt")
        
        self.assertEqual(result["fileId"], "test-id")
        self.assertEqual(result["category"], "invoice")
        self.assertFalse(result.get("error", False))
    
    @patch('services.api_client.requests.post')
    def test_upload_file_network_error(self, mock_post):
        # Mock network error
        mock_post.side_effect = requests.exceptions.ConnectionError("Connection failed")
        
        result = self.client.upload_file(b"test content", "test.txt")
        
        self.assertTrue(result["error"])
        self.assertIn("Connection failed", result["message"])
        self.assertIsNone(result["fileId"])
    
    @patch('services.api_client.requests.post')
    def test_upload_file_server_error(self, mock_post):
        # Mock server error
        mock_response = Mock()
        mock_response.raise_for_status.side_effect = requests.exceptions.HTTPError("500 Server Error")
        mock_post.return_value = mock_response
        
        result = self.client.upload_file(b"test content", "test.txt")
        
        self.assertTrue(result["error"])
        self.assertIn("500 Server Error", result["message"])
    
    @patch('services.api_client.requests.get')
    def test_get_file_result_success(self, mock_get):
        # Mock successful response
        mock_response = Mock()
        mock_response.json.return_value = {
            "fileId": "test-id",
            "category": "invoice",
            "entities": []
        }
        mock_response.raise_for_status.return_value = None
        mock_get.return_value = mock_response
        
        result = self.client.get_file_result("test-id")
        
        self.assertIsNotNone(result)
        self.assertEqual(result["fileId"], "test-id")
    
    @patch('services.api_client.requests.get')
    def test_get_file_result_not_found(self, mock_get):
        # Mock 404 response
        mock_response = Mock()
        mock_response.raise_for_status.side_effect = requests.exceptions.HTTPError("404 Not Found")
        mock_get.return_value = mock_response
        
        result = self.client.get_file_result("non-existent-id")
        
        self.assertIsNone(result)
    
    @patch('services.api_client.requests.get')
    def test_download_file_success(self, mock_get):
        # Mock successful download
        mock_response = Mock()
        mock_response.content = b"file content"
        mock_response.raise_for_status.return_value = None
        mock_get.return_value = mock_response
        
        result = self.client.download_file("test-id")
        
        self.assertEqual(result, b"file content")
    
    @patch('services.api_client.requests.get')
    def test_download_file_not_found(self, mock_get):
        # Mock 404 response
        mock_response = Mock()
        mock_response.raise_for_status.side_effect = requests.exceptions.HTTPError("404 Not Found")
        mock_get.return_value = mock_response
        
        result = self.client.download_file("non-existent-id")
        
        self.assertIsNone(result)
    
    def test_base_url_configuration(self):
        custom_client = ApiClient("http://custom:9000")
        self.assertEqual(custom_client.base_url, "http://custom:9000")
    
    def test_default_base_url(self):
        default_client = ApiClient()
        self.assertEqual(default_client.base_url, "http://localhost:8080")

if __name__ == '__main__':
    unittest.main()
