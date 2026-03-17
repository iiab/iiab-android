#!/bin/bash

# ==========================================
# Terminal Color Codes
# ==========================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ==========================================
# Configuration Variables
# ==========================================
# Dynamically resolve the repository root based on the script's location
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SITE_SRC="$REPO_DIR/"
DEST_DIR="/library/www/html/home"

echo -e "${CYAN}Starting site deployment and synchronization...${NC}"

# Ensure source directory exists before attempting to sync
if [ ! -d "$SITE_SRC" ]; then
    echo -e "${RED}Fatal Error: Source directory not found at $SITE_SRC.${NC}"
    exit 1
fi

# Ensure destination directory exists, create if it does not
if [ ! -d "$DEST_DIR" ]; then
    echo -e "Destination directory $DEST_DIR does not exist. Creating it now..."
    mkdir -p "$DEST_DIR"
fi

# ==========================================
# Audit Phase: Detect Destination Drift
# ==========================================
echo -e "Auditing $DEST_DIR for local modifications or orphaned files..."

# Perform a dry-run rsync (-n) to detect differences.
# Detects & delete files that are different at the destination not origin.
LOCAL_MODS=$(rsync -ani --delete "$SITE_SRC/" "$DEST_DIR/" | grep -E '^>fc|^\*deleting')

if [ -n "$LOCAL_MODS" ]; then
    echo -e "\n${YELLOW}WARNING: Destination drift detected.${NC}"
    echo -e "The following files in the live directory will be OVERWRITTEN or DELETED to match the clean repository state:\n"
    
    # Format the rsync output for readability
    echo "$LOCAL_MODS" | awk '{print "  - " $2}'
    echo ""
    
    read -p "Acknowledge and proceed with deployment? (y/N): " -n 1 -r
    echo ""
    
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${RED}Deployment aborted by user. The live site remains unchanged.${NC}"
        exit 1
    fi
else
    echo -e "No conflicts detected. Destination is clean or ready for new files."
fi

# ==========================================
# Synchronization Phase
# ==========================================
echo -e "Synchronizing files to the live environment..."

# Execute the actual sync. The --delete flag ensures orphaned files are removed.
rsync -a --delete "$SITE_SRC/" "$DEST_DIR/"

echo -e "${GREEN}Deployment completed successfully. The live site is now a perfect mirror of the local repository.${NC}"
