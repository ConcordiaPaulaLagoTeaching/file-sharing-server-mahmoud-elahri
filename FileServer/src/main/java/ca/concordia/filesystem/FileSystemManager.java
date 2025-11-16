package ca.concordia.filesystem;

import ca.concordia.filesystem.datastructures.FEntry;
import ca.concordia.filesystem.datastructures.FNode;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * FileSystemManager - Manages a simulated file system stored in a single disk
 * file.
 * 
 * Features:
 * - Persistent storage using RandomAccessFile
 * - Block-based allocation (linked list of blocks per file)
 * - Thread-safe operations with reader-writer locks
 * - Support for create, delete, read, write, and list operations
 * 
 * File System Layout:
 * [Metadata Blocks: FEntry array | FNode array | Free block bitmap]
 * [Data Blocks: File contents]
 * 
 * Synchronization:
 * - Global lock (synchronized) for metadata operations
 * - Per-file ReadWriteLock for data operations
 * - Multiple readers OR single writer per file
 * 
 * @author Mahmoud Elashri
 */
public class FileSystemManager {

    private final int MAXFILES;
    private final int MAXBLOCKS;
    private final int BLOCK_SIZE = 128;

    private final RandomAccessFile disk;
    private final String diskFileName;

    private FEntry[] fileEntries; // Array of file entries
    private FNode[] fileNodes; // Array of file nodes
    private boolean[] freeBlockList; // Bitmap for free data blocks

    // Per-file locks for reader-writer synchronization
    private final Map<String, ReadWriteLock> fileLocks = new HashMap<>();
    private final Object fileLocksLock = new Object();

    // Metadata region sizes
    private int metadataBlocks;
    private int dataBlockStart;

    /**
     * Initializes the file system manager.
     * 
     * If the backing file doesn't exist, creates a new file system.
     * If it exists, loads the existing file system from disk.
     * 
     * @param filename  Path to the backing file
     * @param totalSize Total size of the file system in bytes
     * @throws IOException If disk I/O fails
     */
    public FileSystemManager(String filename, int totalSize) throws IOException {
        this.diskFileName = filename;
        this.MAXBLOCKS = totalSize / BLOCK_SIZE;
        this.MAXFILES = 5; // Default from starter code

        File diskFile = new File(filename);
        boolean isNewFile = !diskFile.exists();

        this.disk = new RandomAccessFile(filename, "rws");

        // Calculate metadata size
        // FEntry: 11 bytes (name) + 2 bytes (size) + 2 bytes (firstBlock) = 15 bytes
        // FNode: 4 bytes (blockIndex) + 4 bytes (next) = 8 bytes
        int fentryArraySize = MAXFILES * 15;
        int fnodeArraySize = MAXBLOCKS * 8;
        int freeBlockListSize = MAXBLOCKS; // 1 byte per block

        int totalMetadataSize = fentryArraySize + fnodeArraySize + freeBlockListSize;
        metadataBlocks = (int) Math.ceil((double) totalMetadataSize / BLOCK_SIZE);
        dataBlockStart = metadataBlocks;

        // Initialize arrays
        this.fileEntries = new FEntry[MAXFILES];
        this.fileNodes = new FNode[MAXBLOCKS];
        this.freeBlockList = new boolean[MAXBLOCKS];

        if (isNewFile) {
            initializeNewFileSystem();
        } else {
            loadFileSystem();
        }
    }

    private void initializeNewFileSystem() throws IOException {
        // Initialize all entries as null/empty
        for (int i = 0; i < MAXFILES; i++) {
            fileEntries[i] = null;
        }

        // Initialize all fnodes
        for (int i = 0; i < MAXBLOCKS; i++) {
            fileNodes[i] = new FNode(i, -1);
        }

        // Mark metadata blocks as used, data blocks as free
        for (int i = 0; i < MAXBLOCKS; i++) {
            freeBlockList[i] = (i >= dataBlockStart);
        }

        // Ensure file is large enough
        disk.setLength((long) MAXBLOCKS * BLOCK_SIZE);

        // Save initial state
        saveMetadata();
    }

    private void loadFileSystem() throws IOException {
        disk.seek(0);

        // Read FEntry array
        for (int i = 0; i < MAXFILES; i++) {
            byte[] nameBytes = new byte[11];
            disk.readFully(nameBytes);
            String name = new String(nameBytes).trim();
            short size = disk.readShort();
            short firstBlock = disk.readShort();

            if (name.isEmpty() || firstBlock == -1) {
                fileEntries[i] = null;
            } else {
                fileEntries[i] = new FEntry(name, size, firstBlock);
                // Initialize lock for existing file
                synchronized (fileLocksLock) {
                    fileLocks.put(name, new ReentrantReadWriteLock());
                }
            }
        }

        // Read FNode array
        for (int i = 0; i < MAXBLOCKS; i++) {
            int blockIndex = disk.readInt();
            int next = disk.readInt();
            fileNodes[i] = new FNode(blockIndex, next);
        }

        // Read free block list
        for (int i = 0; i < MAXBLOCKS; i++) {
            freeBlockList[i] = disk.readByte() != 0;
        }
    }

    private void saveMetadata() throws IOException {
        disk.seek(0);

        // Write FEntry array
        for (int i = 0; i < MAXFILES; i++) {
            if (fileEntries[i] == null) {
                // Write empty entry
                byte[] emptyName = new byte[11];
                disk.write(emptyName);
                disk.writeShort(0);
                disk.writeShort(-1);
            } else {
                // Write file entry
                byte[] nameBytes = new byte[11];
                byte[] srcBytes = fileEntries[i].getFilename().getBytes();
                System.arraycopy(srcBytes, 0, nameBytes, 0, Math.min(srcBytes.length, 11));
                disk.write(nameBytes);
                disk.writeShort(fileEntries[i].getFilesize());
                disk.writeShort(fileEntries[i].getFirstBlock());
            }
        }

        // Write FNode array
        for (int i = 0; i < MAXBLOCKS; i++) {
            disk.writeInt(fileNodes[i].getBlockIndex());
            disk.writeInt(fileNodes[i].getNext());
        }

        // Write free block list
        for (int i = 0; i < MAXBLOCKS; i++) {
            disk.writeByte(freeBlockList[i] ? 1 : 0);
        }

        disk.getFD().sync();
    }

    /**
     * Creates a new empty file in the file system.
     * 
     * The file is created with size 0 and no blocks allocated.
     * Blocks are allocated when data is written to the file.
     * 
     * @param fileName Name of the file to create (max 11 characters)
     * @throws Exception If filename is too long, file already exists, or no free
     *                   slots
     */
    public void createFile(String fileName) throws Exception {
        if (fileName.length() > 11) {
            throw new Exception("ERROR: filename too large");
        }

        synchronized (this) {
            // Check if file already exists
            for (int i = 0; i < MAXFILES; i++) {
                if (fileEntries[i] != null && fileEntries[i].getFilename().equals(fileName)) {
                    throw new Exception("ERROR: file " + fileName + " already exists");
                }
            }

            // Find free entry slot
            int freeEntryIndex = -1;
            for (int i = 0; i < MAXFILES; i++) {
                if (fileEntries[i] == null) {
                    freeEntryIndex = i;
                    break;
                }
            }

            if (freeEntryIndex == -1) {
                throw new Exception("ERROR: maximum number of files reached");
            }

            // Create empty file entry (no blocks allocated yet)
            fileEntries[freeEntryIndex] = new FEntry(fileName, (short) 0, (short) -1);

            // Initialize lock for new file
            synchronized (fileLocksLock) {
                fileLocks.put(fileName, new ReentrantReadWriteLock());
            }

            saveMetadata();
        }
    }

    /**
     * Deletes a file from the file system.
     * 
     * All blocks used by the file are freed and zeroed out for security.
     * The operation is atomic - either completes fully or not at all.
     * 
     * @param fileName Name of the file to delete
     * @throws Exception If file does not exist
     */
    public void deleteFile(String fileName) throws Exception {
        synchronized (this) {
            // Find the file
            int entryIndex = -1;
            for (int i = 0; i < MAXFILES; i++) {
                if (fileEntries[i] != null && fileEntries[i].getFilename().equals(fileName)) {
                    entryIndex = i;
                    break;
                }
            }

            if (entryIndex == -1) {
                throw new Exception("ERROR: file " + fileName + " does not exist");
            }

            FEntry entry = fileEntries[entryIndex];

            // Get write lock for this file to ensure no one is reading/writing
            ReadWriteLock lock = getFileLock(fileName);
            lock.writeLock().lock();
            try {
                // Free all blocks and zero them
                if (entry.getFirstBlock() != -1) {
                    int currentBlock = entry.getFirstBlock();
                    while (currentBlock != -1) {
                        // Zero out the block (security requirement)
                        zeroBlock(currentBlock);

                        // Mark as free
                        freeBlockList[currentBlock] = true;

                        // Move to next block
                        int nextBlock = fileNodes[currentBlock].getNext();
                        fileNodes[currentBlock].setNext(-1);
                        currentBlock = nextBlock;
                    }
                }

                // Remove entry
                fileEntries[entryIndex] = null;

                saveMetadata();
            } finally {
                lock.writeLock().unlock();
                // Remove lock from map
                synchronized (fileLocksLock) {
                    fileLocks.remove(fileName);
                }
            }
        }
    }

    /**
     * Writes content to a file, replacing any existing content.
     * 
     * The operation is atomic - if there's insufficient space, no changes are made.
     * Old blocks are freed and new blocks are allocated as needed.
     * 
     * Synchronization: Acquires write lock (blocks all readers and other writers)
     * 
     * @param fileName Name of the file to write
     * @param contents Byte array of content to write
     * @throws Exception If file doesn't exist or insufficient space
     */
    public void writeFile(String fileName, byte[] contents) throws Exception {
        ReadWriteLock lock = getFileLock(fileName);
        if (lock == null) {
            throw new Exception("ERROR: file " + fileName + " does not exist");
        }

        lock.writeLock().lock();
        try {
            synchronized (this) {
                // Find the file
                int entryIndex = -1;
                for (int i = 0; i < MAXFILES; i++) {
                    if (fileEntries[i] != null && fileEntries[i].getFilename().equals(fileName)) {
                        entryIndex = i;
                        break;
                    }
                }

                if (entryIndex == -1) {
                    throw new Exception("ERROR: file " + fileName + " does not exist");
                }

                FEntry entry = fileEntries[entryIndex];

                // Calculate blocks needed
                int blocksNeeded = (int) Math.ceil((double) contents.length / BLOCK_SIZE);

                // Check if enough free blocks
                int freeBlockCount = 0;
                for (int i = dataBlockStart; i < MAXBLOCKS; i++) {
                    if (freeBlockList[i])
                        freeBlockCount++;
                }

                if (blocksNeeded > freeBlockCount) {
                    throw new Exception("ERROR: file too large");
                }

                // Free old blocks if any
                if (entry.getFirstBlock() != -1) {
                    int currentBlock = entry.getFirstBlock();
                    while (currentBlock != -1) {
                        int nextBlock = fileNodes[currentBlock].getNext();
                        freeBlockList[currentBlock] = true;
                        fileNodes[currentBlock].setNext(-1);
                        currentBlock = nextBlock;
                    }
                }

                // Allocate new blocks
                if (blocksNeeded == 0) {
                    entry.setFilesize((short) 0);
                    fileEntries[entryIndex] = new FEntry(fileName, (short) 0, (short) -1);
                } else {
                    int[] allocatedBlocks = new int[blocksNeeded];
                    int allocated = 0;
                    for (int i = dataBlockStart; i < MAXBLOCKS && allocated < blocksNeeded; i++) {
                        if (freeBlockList[i]) {
                            allocatedBlocks[allocated++] = i;
                            freeBlockList[i] = false;
                        }
                    }

                    // Link blocks
                    for (int i = 0; i < blocksNeeded - 1; i++) {
                        fileNodes[allocatedBlocks[i]].setNext(allocatedBlocks[i + 1]);
                    }
                    fileNodes[allocatedBlocks[blocksNeeded - 1]].setNext(-1);

                    // Write data to blocks
                    for (int i = 0; i < blocksNeeded; i++) {
                        int blockNum = allocatedBlocks[i];
                        int offset = i * BLOCK_SIZE;
                        int length = Math.min(BLOCK_SIZE, contents.length - offset);
                        writeBlock(blockNum, contents, offset, length);
                    }

                    // Update entry
                    fileEntries[entryIndex] = new FEntry(fileName, (short) contents.length, (short) allocatedBlocks[0]);
                }

                saveMetadata();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Reads the contents of a file.
     * 
     * Synchronization: Acquires read lock (allows concurrent readers, blocks
     * writers)
     * 
     * @param fileName Name of the file to read
     * @return Byte array containing file contents
     * @throws Exception If file does not exist
     */
    public byte[] readFile(String fileName) throws Exception {
        ReadWriteLock lock = getFileLock(fileName);
        if (lock == null) {
            throw new Exception("ERROR: file " + fileName + " does not exist");
        }

        lock.readLock().lock();
        try {
            synchronized (this) {
                // Find the file
                FEntry entry = null;
                for (int i = 0; i < MAXFILES; i++) {
                    if (fileEntries[i] != null && fileEntries[i].getFilename().equals(fileName)) {
                        entry = fileEntries[i];
                        break;
                    }
                }

                if (entry == null) {
                    throw new Exception("ERROR: file " + fileName + " does not exist");
                }

                int fileSize = entry.getFilesize();
                if (fileSize == 0) {
                    return new byte[0];
                }

                byte[] result = new byte[fileSize];
                int currentBlock = entry.getFirstBlock();
                int offset = 0;

                while (currentBlock != -1 && offset < fileSize) {
                    int length = Math.min(BLOCK_SIZE, fileSize - offset);
                    readBlock(currentBlock, result, offset, length);
                    offset += length;
                    currentBlock = fileNodes[currentBlock].getNext();
                }

                return result;
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Lists all files in the file system.
     * 
     * @return Array of filenames (empty array if no files)
     */
    public String[] listFiles() {
        synchronized (this) {
            ArrayList<String> files = new ArrayList<>();
            for (int i = 0; i < MAXFILES; i++) {
                if (fileEntries[i] != null) {
                    files.add(fileEntries[i].getFilename());
                }
            }
            return files.toArray(new String[0]);
        }
    }

    private ReadWriteLock getFileLock(String fileName) {
        synchronized (fileLocksLock) {
            return fileLocks.get(fileName);
        }
    }

    private void writeBlock(int blockNum, byte[] data, int srcOffset, int length) throws IOException {
        long diskOffset = (long) blockNum * BLOCK_SIZE;
        disk.seek(diskOffset);
        disk.write(data, srcOffset, length);

        // Zero out rest of block if partial write
        if (length < BLOCK_SIZE) {
            byte[] zeros = new byte[BLOCK_SIZE - length];
            disk.write(zeros);
        }
    }

    private void readBlock(int blockNum, byte[] dest, int destOffset, int length) throws IOException {
        long diskOffset = (long) blockNum * BLOCK_SIZE;
        disk.seek(diskOffset);
        disk.readFully(dest, destOffset, length);
    }

    private void zeroBlock(int blockNum) throws IOException {
        long diskOffset = (long) blockNum * BLOCK_SIZE;
        disk.seek(diskOffset);
        byte[] zeros = new byte[BLOCK_SIZE];
        disk.write(zeros);
    }

    public void close() throws IOException {
        if (disk != null) {
            saveMetadata();
            disk.close();
        }
    }
}
