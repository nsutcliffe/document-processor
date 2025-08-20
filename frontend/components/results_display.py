import streamlit as st
import pandas as pd
from typing import Dict, Any, List
import io

def display_results(result: Dict[str, Any]) -> None:
    """Display processing results in a structured format."""
    
    st.subheader("📊 Processing Results")
    
    # Category and confidence
    col1, col2 = st.columns(2)
    with col1:
        st.metric("Category", result.get('category', 'Unknown'))
    with col2:
        confidence = result.get('confidenceScore', 0)
        st.metric("Confidence", f"{confidence:.1%}")
    
    # File information
    with st.expander("📁 File Information", expanded=False):
        st.write(f"**File ID:** `{result.get('fileId', 'N/A')}`")
        st.write(f"**Filename:** {result.get('filename', 'N/A')}")
        st.write(f"**File Size:** {result.get('fileSize', 0):,} bytes")
        st.write(f"**File Type:** {result.get('fileType', 'N/A')}")
    
    # Extracted entities
    entities = result.get('entities', [])
    if entities:
        st.subheader("🏷️ Extracted Entities")
        
        # Group entities by type
        entity_groups = {}
        for entity in entities:
            entity_type = entity.get('type', 'unknown')
            if entity_type not in entity_groups:
                entity_groups[entity_type] = []
            entity_groups[entity_type].append(entity)
        
        # Display entities in columns
        for entity_type, entity_list in entity_groups.items():
            with st.expander(f"{entity_type.replace('_', ' ').title()} ({len(entity_list)})", expanded=True):
                for entity in entity_list:
                    confidence = entity.get('confidence', 0)
                    confidence_color = "🟢" if confidence > 0.8 else "🟡" if confidence > 0.5 else "🔴"
                    st.write(f"{confidence_color} **{entity.get('value', 'N/A')}** (confidence: {confidence:.1%})")
    else:
        st.info("No entities extracted from this document.")
    
    # Extracted dates
    dates = result.get('dates', [])
    if dates:
        st.subheader("📅 Extracted Dates")
        for date in dates:
            st.write(f"• {date}")
    
    # Extracted tables
    tables = result.get('tables', [])
    if tables:
        st.subheader("📋 Extracted Tables")
        
        for i, table in enumerate(tables):
            table_name = table.get('table_name', f'Table {i+1}')
            headers = table.get('headers', [])
            rows = table.get('rows', [])
            
            with st.expander(f"📊 {table_name}", expanded=True):
                if headers and rows:
                    # Create DataFrame
                    try:
                        df = pd.DataFrame(rows, columns=headers)
                        st.dataframe(df, use_container_width=True)
                        
                        # CSV download button
                        csv_buffer = io.StringIO()
                        df.to_csv(csv_buffer, index=False)
                        csv_data = csv_buffer.getvalue()
                        
                        st.download_button(
                            label=f"📥 Download {table_name} as CSV",
                            data=csv_data,
                            file_name=f"{table_name.lower().replace(' ', '_')}.csv",
                            mime="text/csv"
                        )
                    except Exception as e:
                        st.error(f"Error displaying table: {str(e)}")
                        st.json(table)
                else:
                    st.write("Empty table or invalid format")
                    st.json(table)


def display_error(error_message: str) -> None:
    """Display an error message."""
    st.error(f"❌ **Processing Failed**")
    
    # Show user-friendly error message
    st.write(f"**Issue:** {error_message}")
    
    with st.expander("💡 Troubleshooting Tips"):
        if "API configuration" in error_message:
            st.write("""
            **API Configuration Issue:**
            - The OpenRouter API key may not be set correctly
            - Contact the administrator to verify the API configuration
            """)
        elif "categorize" in error_message or "extract" in error_message:
            st.write("""
            **AI Processing Issue:**
            - The AI service may be temporarily unavailable
            - Try uploading a different file format (PDF works best)
            - Wait a few minutes and try again
            """)
        elif "timeout" in error_message:
            st.write("""
            **Timeout Issue:**
            - Your file may be too large to process quickly
            - Try with a smaller file (under 5MB recommended)
            - Ensure you have a stable internet connection
            """)
        elif "busy" in error_message or "wait" in error_message:
            st.write("""
            **Service Busy:**
            - The AI service is currently handling many requests
            - Wait 30-60 seconds and try again
            - Consider trying during off-peak hours
            """)
        else:
            st.write("""
            **General troubleshooting:**
            - Make sure the backend server is running on `http://localhost:8080`
            - Check that your file is in a supported format (PDF, PNG, JPEG)
            - Try with a different file to see if the issue persists
            - Refresh the page and try again
            """)
    
    # Retry button
    if st.button("🔄 Try Again"):
        st.rerun()


def display_processing() -> None:
    """Display processing indicator."""
    st.info("🔄 Processing your document... This may take a few moments.")
    
    # Progress bar (indeterminate)
    progress_bar = st.progress(0)
    import time
    for i in range(100):
        time.sleep(0.01)
        progress_bar.progress(i + 1)
