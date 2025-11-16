package ca.concordia.filesystem.datastructures;

/**
 * File Node (FNode) - Represents a node in the linked list of data blocks.
 * 
 * Structure:
 * - blockIndex: int (index of this block in the disk)
 * - next: int (index of next block in chain, -1 if last block)
 * 
 * Total size: 8 bytes (4 + 4)
 * 
 * Forms a linked list structure allowing files to span multiple non-contiguous
 * blocks.
 */
public class FNode {

    private int blockIndex;
    private int next;

    /**
     * Creates a new file node with no next block.
     * 
     * @param blockIndex Index of this block
     */
    public FNode(int blockIndex) {
        this.blockIndex = blockIndex;
        this.next = -1;
    }

    /**
     * Creates a new file node with specified next block.
     * 
     * @param blockIndex Index of this block
     * @param next       Index of next block in chain (-1 if last)
     */
    public FNode(int blockIndex, int next) {
        this.blockIndex = blockIndex;
        this.next = next;
    }

    public int getBlockIndex() {
        return blockIndex;
    }

    public void setBlockIndex(int blockIndex) {
        this.blockIndex = blockIndex;
    }

    public int getNext() {
        return next;
    }

    public void setNext(int next) {
        this.next = next;
    }
}
