import streamlit as st
from typing import Optional
import io

def file_upload_component() -> Optional[tuple]:
    """File upload component that returns (file_bytes, filename) if a file is uploaded."""
    
    st.subheader("📄 Document Upload")
    st.write("Upload a document for categorization and content extraction.")
    
    # Supported file types
    st.info("**Supported formats:** PDF, PNG, JPEG, JPG")
    
    uploaded_file = st.file_uploader(
        "Choose a file",
        type=['pdf', 'png', 'jpg', 'jpeg'],
        help="Select a document to analyze"
    )
    
    if uploaded_file is not None:
        # Show file details
        file_details = {
            "Filename": uploaded_file.name,
            "File size": f"{uploaded_file.size:,} bytes",
            "File type": uploaded_file.type
        }
        
        with st.expander("📋 File Details", expanded=False):
            for key, value in file_details.items():
                st.write(f"**{key}:** {value}")
        
        # Read file bytes
        file_bytes = uploaded_file.read()
        return file_bytes, uploaded_file.name
    
    return None

