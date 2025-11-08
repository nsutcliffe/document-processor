import streamlit as st
import sys
import os

# Add the current directory to Python path for imports
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from services.api_client import ApiClient
from components.file_upload import file_upload_component
from components.results_display import display_results, display_error, display_processing

# Page configuration
st.set_page_config(
    page_title="Tunic Pay - Document Processor",
    page_icon="📄",
    layout="wide",
    initial_sidebar_state="expanded"
)

# Initialize API client
@st.cache_resource
def get_api_client():
    return ApiClient()

def main():
    # Header
    st.title("📄 Tunic Pay Document Processor")
    st.markdown("---")
    
    # Sidebar with information
    with st.sidebar:
        st.header("ℹ️ About")
        st.write("""
        This application processes documents using AI to:
        - **Categorize** documents into predefined types
        - **Extract** key entities (names, dates, amounts, etc.)
        - **Parse** tables and structured data
        - **Provide** confidence scores for all extractions
        """)
        
        st.header("🎯 Supported Categories")
        categories = [
            "📧 Invoice",
            "🛒 Marketplace Listing Screenshot", 
            "💬 Chat Screenshot",
            "🌐 Website Screenshot",
            "📋 Other"
        ]
        for category in categories:
            st.write(f"• {category}")
        
        st.header("🔧 System Status")
        api_client = get_api_client()
        
        # Simple health check
        try:
            # Try to make a request to check if backend is running
            import requests
            response = requests.get(f"{api_client.base_url}/test", timeout=5)
            if response.status_code in [200, 404]:  # 404 is fine, means server is running
                st.success("✅ Backend Connected")
            else:
                st.warning("⚠️ Backend Issues")
        except:
            st.error("❌ Backend Offline")
    
    # Main content area
    api_client = get_api_client()
    
    # Sidebar: recent files list
    with st.sidebar:
        st.header("🗂️ Recent Files")
        files = api_client.list_files() or []
        if files:
            # Build options as "filename (status)"
            options = {f"{f['filename']} ({f['status']}{' • ' + f['category'] if f.get('category') else ''})": f['id'] for f in files}
            selection = st.selectbox("Select a file", list(options.keys()))
            if st.button("Load Selected"):
                selected_id = options[selection]
                result = api_client.get_file_result(selected_id) or {"error": True, "message": "Not found"}
                st.session_state['last_result'] = result
                st.rerun()
        else:
            st.caption("No files yet.")

    # File upload section
    file_data = file_upload_component()
    
    if file_data:
        file_bytes, filename = file_data
        
        # Process button
        if st.button("🚀 Process Document", type="primary", use_container_width=True):
            # Clear previous results when starting new processing
            if 'last_result' in st.session_state:
                del st.session_state['last_result']
            
            # Show processing indicator
            with st.spinner("Processing document..."):
                # Upload and process file
                result = api_client.upload_file(file_bytes, filename)
                
                # Store result in session state
                st.session_state['last_result'] = result
                
                # Force UI refresh
                st.rerun()
    
    # Display results if available
    if 'last_result' in st.session_state:
        result = st.session_state['last_result']
        
        st.markdown("---")
        
        # Add clear results button
        col1, col2 = st.columns([3, 1])
        with col2:
            if st.button("🗑️ Clear Results"):
                del st.session_state['last_result']
                st.rerun()
        
        # Check for different types of errors/issues
        if result.get('error'):
            # Direct API error
            display_error(result.get('message', 'Unknown error occurred'))
        elif result.get('category') == 'processing':
            # File is still being processed
            st.info("🔄 Your document is still being processed. Please wait...")
        elif result.get('category') == 'other' and result.get('confidenceScore', 0) == 0.0:
            # Processing failed but returned a response
            display_error("Unable to categorize this document. The AI service may be having issues.")
        else:
            # Successful processing
            display_results(result)
            
            # Download original file section
            if result.get('fileId'):
                st.markdown("---")
                st.subheader("💾 Download Original File")
                
                if st.button("📥 Download Original"):
                    file_content = api_client.download_file(result['fileId'])
                    if file_content:
                        st.download_button(
                            label="💾 Save File",
                            data=file_content,
                            file_name=result.get('filename', 'document'),
                            mime="application/octet-stream"
                        )
                    else:
                        st.error("Failed to download file")
    
    # Footer
    st.markdown("---")
    st.markdown(
        """
        <div style='text-align: center; color: #666; font-size: 0.8em;'>
            Built with Streamlit • Powered by OpenRouter AI • Tunic Pay Document Processor
        </div>
        """,
        unsafe_allow_html=True
    )

if __name__ == "__main__":
    main()
