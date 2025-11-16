package ca.concordia.filesystem.datastructures;

/**
 * File Entry (FEntry) - Represents metadata for a single file in the file
 * system.
 * 
 * Structure:
 * - filename: String (max 11 characters)
 * - filesize: short (actual size in bytes)
 * - firstBlock: short (index of first data block, -1 if empty)
 * 
 * Total size: 15 bytes (11 + 2 + 2)
 */
public class FEntry {

    private String filename;
    private short filesize;
    private short firstBlock; // Index of first data block (-1 if no blocks allocated)

    /**
     * Creates a new file entry.
     * 
     * @param filename   Name of the file (max 11 characters)
     * @param filesize   Size of the file in bytes
     * @param firstblock Index of the first data block (-1 if empty)
     * @throws IllegalArgumentException if filename exceeds 11 characters
     */
    public FEntry(String filename, short filesize, short firstblock) throws IllegalArgumentException {
        // Check filename is max 11 bytes long
        if (filename.length() > 11) {
            throw new IllegalArgumentException("Filename cannot be longer than 11 characters.");
        }
        this.filename = filename;
        this.filesize = filesize;
        this.firstBlock = firstblock;
    }

    // Getters and Setters
    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        if (filename.length() > 11) {
            throw new IllegalArgumentException("Filename cannot be longer than 11 characters.");
        }
        this.filename = filename;
    }

    public short getFilesize() {
        return filesize;
    }

    public void setFilesize(short filesize) {
        if (filesize < 0) {
            throw new IllegalArgumentException("Filesize cannot be negative.");
        }
        this.filesize = filesize;
    }

    public short getFirstBlock() {
        return firstBlock;
    }
}
