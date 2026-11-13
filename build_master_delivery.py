import os
import sys
import shutil
import hashlib
import zipfile
import json
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from reportlab.lib.pagesizes import letter
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, KeepTogether, HRFlowable
)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle

PROJECT_ROOT = r"D:\sharyar\SMART VALUATE"
DELIVERY_ROOT = os.path.join(PROJECT_ROOT, "SECURE_VAULT_PRO_FINAL_DELIVERY")
DOCS_MD_ROOT = os.path.join(PROJECT_ROOT, "SECURE_VAULT_PRO_DOCUMENTATION")

# 1. Create directory structure
dirs = [
    DELIVERY_ROOT,
    os.path.join(DELIVERY_ROOT, "ANDROID_RELEASE"),
    os.path.join(DELIVERY_ROOT, "ANDROID_SOURCE"),
    os.path.join(DELIVERY_ROOT, "WEB_APPLICATION"),
    os.path.join(DELIVERY_ROOT, "BLOGGER_XML_THEME"),
    os.path.join(DELIVERY_ROOT, "BACKEND_AND_DATABASE"),
    os.path.join(DELIVERY_ROOT, "DOCUMENTATION_MARKDOWN"),
    os.path.join(DELIVERY_ROOT, "DOCUMENTATION_WORD"),
    os.path.join(DELIVERY_ROOT, "DOCUMENTATION_PDF"),
    os.path.join(DELIVERY_ROOT, "MASTER_PROMPT"),
    os.path.join(DELIVERY_ROOT, "INDIVIDUAL_PROMPTS"),
    os.path.join(DELIVERY_ROOT, "SOURCE_CODE_PACKAGES"),
    os.path.join(DELIVERY_ROOT, "TEST_REPORTS"),
    os.path.join(DELIVERY_ROOT, "VERSION_HISTORY"),
    os.path.join(DELIVERY_ROOT, "BACKUP_AND_RECOVERY"),
    DOCS_MD_ROOT
]

for d in dirs:
    os.makedirs(d, exist_ok=True)

print("Created folder structure successfully.")
