# File Sharing Server – Design Report

**Student:** Mahmoud Elashri  
**Course:** COEN 346 – Operating Systems  
**Assignment:** Programming Assignment #2

---

## 1. Data Structures and Functions

This section explains the main classes and global data that I added or modified.  
Instead of formal UML, I describe the “shape” of each class and how it fits into the design.

### 1.1 File system structures

#### `FEntry`

`FEntry` is one record in the file table. Conceptually it is:

```java
class FEntry {
    String filename;   // up to 11 chars
    short filesize;    // number of bytes stored in the file
    short firstBlock;  // index of first data block, -1 if file is empty
}
```

Each entry describes a single file in the simulated disk.  
The fields are fixed‑size so that the whole table can be laid out sequentially at the start of the disk file.  
I use `short` for the numeric fields because the assignment limits the number of blocks, so a 16‑bit value is enough.

#### `FNode`

`FNode` represents one node in the “block chain” of a file:

```java
class FNode {
    int blockIndex;  // which block this node refers to
    int next;        // index of next node in the chain, -1 if last
}
```

Logically, each file is a linked list of blocks. `blockIndex` says where the data lives,  
and `next` points to the next block for that file. This keeps the data structure simple and
lets files use non‑contiguous blocks on disk.

#### `FileSystemManager`

`FileSystemManager` is the “kernel” of my file system. It owns:

- configuration constants: `MAXFILES`, `MAXBLOCKS`, `BLOCK_SIZE`
- a `RandomAccessFile disk` that represents the backing store
- arrays for metadata:
  - `FEntry[] fileEntries`
  - `FNode[] fileNodes`
  - `boolean[] freeBlockList` (a simple bitmap of which blocks are free)
- synchronization state:
  - `Map<String, ReadWriteLock> fileLocks` – one reader‑writer lock per file
  - `Object fileLocksLock` – protects the map itself
- layout info:
  - `metadataBlocks` – how many blocks are used by the metadata section
  - `dataBlockStart` – index of the first data block

The constructor takes a filename and total size. On first run it creates an empty file system;  
on later runs it reconstructs `fileEntries`, `fileNodes`, and the free‑block bitmap from disk.

The public API that other code uses is:

- `createFile(String name)`
- `deleteFile(String name)`
- `writeFile(String name, byte[] contents)`
- `byte[] readFile(String name)`
- `String[] listFiles()`

These are the operations exposed by the server protocol, so keeping them all here
helps separate “file system logic” from “network logic”.

### 1.2 Server‑side structures

#### `FileServer`

`FileServer` is responsible for networking and high‑level protocol handling.  
Important fields:

- `FileSystemManager fsManager` – shared file system instance
- `int port` – listening port (12345 in this assignment)
- `ServerSocket serverSocket`
- `ExecutorService threadPool` – a cached thread pool for client handlers

The main public methods are:

- `start()` – opens the server socket and starts accepting clients
- `stop()` – closes the socket, shuts down the pool, and closes the file system

#### `ClientHandler`

`ClientHandler` is a private inner class in `FileServer` that implements `Runnable`.  
Each accepted socket is wrapped in a `ClientHandler` and submitted to the thread pool.

Inside `run()`, the handler:

1. Reads one line at a time from the client.
2. Parses it into a command and parameters.
3. Calls the corresponding method on `FileSystemManager`.
4. Sends back either the result or an error string.

There is no shared mutable state inside `ClientHandler` itself; all shared state lives in
`FileSystemManager`, which is protected by locks. This makes the handlers easy to reason about.

---

## 2. Algorithms

In this section I describe how the main operations work, at a level just below the assignment spec.
I focus on how the data structures are used rather than repeating the problem statement.

### 2.1 Initializing and loading the file system

When `FileSystemManager` is created, it checks whether the backing file already exists:

- **New file system**

  - Compute how many bytes are needed for the `FEntry` array, `FNode` array, and free‑block bitmap.
  - Convert that to a number of blocks (`metadataBlocks`) and set `dataBlockStart` right after it.
  - Allocate arrays for `fileEntries`, `fileNodes`, and `freeBlockList`.
  - Mark blocks `< dataBlockStart` as “used” (metadata) and the rest as free.
  - Expand the backing file to the full size and call `saveMetadata()` to write the empty structures.

- **Existing file system**
  - Seek to the beginning of the file.
  - For each entry in the `FEntry` array, read the fixed‑size record and either reconstruct
    an `FEntry` or mark the slot as empty.
  - Read all `FNode` records.
  - Read the free‑block bitmap.
  - For each existing file, create its `ReadWriteLock` and put it in the `fileLocks` map.

The metadata is always written at offset 0, so the layout is predictable and loading is straightforward.

### 2.2 Creating a file

`createFile(name)` performs the following steps inside a `synchronized (this)` block:

1. Check that the name is at most 11 characters; otherwise throw an exception with
   a message that matches the tests.
2. Scan the `fileEntries` array to ensure the filename is not already in use.
3. Scan again to find the first `null` slot.
4. If there is no free slot, signal an error (“maximum number of files reached”).
5. Create a new `FEntry` with size 0 and `firstBlock = -1` and store it in the free slot.
6. Create a `ReadWriteLock` for this filename and add it to `fileLocks`.
7. Call `saveMetadata()` to persist the change.

Because the whole operation is guarded by the monitor on `FileSystemManager`, there is no race
between checking for an existing name and inserting the new entry.

### 2.3 Deleting a file

`deleteFile(name)` also runs under `synchronized (this)`:

1. Find the index of the corresponding `FEntry`. If not found, throw “file … does not exist”.
2. Grab the write lock for that file, so that no other thread can be reading or writing it.
3. Traverse the block chain starting from `firstBlock`:
   - For each block, call `zeroBlock()` to overwrite the 128 bytes with zeros.
   - Mark the block as free in `freeBlockList`.
   - Clear the `next` pointer in the corresponding `FNode`.
4. Remove the `FEntry` from the array.
5. Call `saveMetadata()` and finally release the write lock and remove it from `fileLocks`.

Zeroing blocks when deleting a file is important for the “security” requirement in the spec:
future files that reuse these blocks should not see old data.

### 2.4 Writing to a file

The high‑level idea of `writeFile(name, contents)` is “all or nothing”:
either the entire byte array is stored, or the file is left unchanged.

Steps:

1. Look up the file’s `ReadWriteLock`. If it doesn’t exist, signal “file … does not exist”.
2. Acquire the write lock for that file.
3. Inside `synchronized (this)`, find the `FEntry` for the file.
4. Compute how many blocks are needed:  
   `blocksNeeded = ceil(contents.length / BLOCK_SIZE)`.
5. Count how many free blocks are available in the data region.
   If there are not enough, throw “file too large” and exit without changing anything.
6. Free the old blocks (if any), just like in `deleteFile`, but without zeroing (the new data will overwrite them).
7. Walk through `freeBlockList` and pick the first `blocksNeeded` free blocks.
   - Record their indices in an array.
   - Mark them as used.
8. Link the chosen blocks into a chain using the `fileNodes` array.
9. For each allocated block:
   - Seek to its offset in the backing file.
   - Write up to 128 bytes of the content array.
   - If the last block is only partially used, pad the rest with zeros.
10. Update the `FEntry` with the new size and the first block index.
11. Call `saveMetadata()` and release the write lock.

By checking space before freeing the old blocks, I avoid leaving the file half‑written.

### 2.5 Reading a file

`readFile(name)` is the mirror image of `writeFile`:

1. Look up the file’s `ReadWriteLock` and acquire the **read** lock.
2. Inside `synchronized (this)`, find the corresponding `FEntry`.
3. Allocate a result buffer of length `filesize`.
4. Starting from `firstBlock`, follow the chain of `FNode`s:
   - For each block, seek to the correct offset in the disk file.
   - Read `min(BLOCK_SIZE, remainingBytes)` into the result buffer.
5. When all bytes are read, release the read lock and return the buffer.

Multiple threads can call `readFile` at the same time on the same file, because they only take
the shared read lock. Writers will block until all readers are done.

### 2.6 Listing files

`listFiles()` is simple: under `synchronized (this)`, it loops through `fileEntries`,
collects the non‑null `filename` values into a list, and returns them as a `String[]`.
The server uses this method to implement the `LIST` command.

### 2.7 Server command handling and threads

The server runs an accept loop in `start()`:

1. Create a `ServerSocket` on the configured port.
2. For each accepted `Socket`, create a new `ClientHandler` and submit it to the thread pool.

`ClientHandler.run()` reads commands line by line. The format is deliberately simple:

- `CREATE name`
- `WRITE name content...`
- `READ name`
- `DELETE name`
- `LIST`
- `QUIT`

The handler splits the line on whitespace (up to three parts), switches on the command, and
calls the appropriate `FileSystemManager` method. It always catches exceptions and turns them into
`ERROR: ...` strings so that a bad command cannot crash the server thread.

---

## 3. Rationale

In this section I explain why I chose this particular design and how it addresses the
requirements and corner cases in the assignment.

### 3.1 File system layout choices

Keeping all metadata (file table, node table, and free‑block map) at the beginning of the disk
file has a few advantages:

- The layout is easy to reconstruct on startup because everything is at a fixed offset.
- It keeps file data in a contiguous region, which simplifies block calculations.
- For the small limits in the assignment (5 files, 10 blocks), the extra “wasted” bytes
  in the metadata area are negligible.

I used a simple linked‑list organization for file blocks (via `FNode`). More sophisticated
approaches like indexed allocation would reduce the cost of random access, but for the
block counts and file sizes in this project a linked list is much easier to implement and debug.

### 3.2 Synchronization strategy

The assignment explicitly requires that multiple clients can read the same file at the same time,
but only one writer is allowed, and no readers while a writer is active. A per‑file
`ReadWriteLock` matches this requirement directly:

- All readers share the read lock → they can proceed together.
- Writers take the write lock → they have exclusive access.

I still use `synchronized (this)` around the internal metadata operations to protect arrays and
the free‑block map, but those critical sections are short and purely in memory. This two‑level
strategy (file‑level read/write lock + global monitor) keeps the code simple while avoiding
obvious races.

To reduce the chance of deadlocks, I always acquire locks in the same order:
first the per‑file lock, then the global monitor. I also release them in the opposite order.
Because there is only one global monitor and each operation works on a single file at a time,
there is no cyclic dependency between threads.

### 3.3 Server and threading choices

I chose a cached thread pool for the server for a few reasons:

- The number of concurrent clients is not fixed in advance.
- The pool reuses threads for short‑lived connections, which is cheaper than creating
  a brand‑new thread each time.
- The code is very compact: `Executors.newCachedThreadPool()` takes care of most of the logic.

An alternative would be a fixed‑size pool, which would give more predictable memory usage.
For this assignment, where the tests emphasize “hundreds of clients” but not thousands, the
cached pool is a good compromise between simplicity and scalability.

### 3.4 Trade‑offs and limitations

Some design decisions deliberately trade generality for simplicity:

- File lookup is a linear scan over at most 5 entries, which is trivial at this scale.
  A hash map or tree would only add overhead.
- Files are accessed sequentially via the block chain; random seeks inside a file would be slow.
  Again, this is acceptable given the maximum sizes.
- There is no directory hierarchy; all files live in a single flat namespace.

On the positive side, the implementation is easy to reason about, and it would not be too hard
to extend. For example, increasing `MAXFILES` and `MAXBLOCKS` mostly affects the array sizes,
and adding permissions would only require extra fields in `FEntry` and some checks in the
server commands.

---

## 4. Testing and Validation

I relied on the provided JUnit tests plus manual experiments.

- **`FileSystemTests`** cover creating, deleting, reading, and writing files (including long
  contents that span several blocks). They also check error cases such as too‑long filenames.
- **`ServerTests`** verify that malformed commands return `ERROR` responses but do not crash
  the server, that the `LIST` command works, and that data persists when the server is
  stopped and restarted.
- **`ThreadManagementTests`** stress the multithreading part by running many clients in
  parallel, mixing `CREATE`, `READ`, and `WRITE` on shared files. Passing these tests gives
  some confidence that the locking scheme avoids deadlocks and race conditions.

In addition to the tests, I ran the client manually, issuing a mix of commands and watching the
server output and the contents of the backing file between runs.

---

## 5. Conclusion

The final design separates responsibilities cleanly:

- `FileSystemManager` implements a small but consistent file system with persistence.
- `FileServer` and `ClientHandler` translate text commands into file system operations.
- Reader‑writer locks ensure that the file system behaves correctly under concurrent load.

Overall, the solution favors clarity and safety over maximum performance, which matches the
goals of the assignment. If I had more time, the next steps would be to support directories,
finer‑grained error reporting, and perhaps a more sophisticated block allocation strategy.
