package eu.wohlben.qits.artifacts.gc.dto;

/**
 * The disk half of a sweep receipt: what the blob store's compaction gave back to the volume after
 * the run's deletes (qits-2248).
 *
 * <p><b>Why a receipt needs this beside {@link GcSweepOutcome}.</b> {@code
 * GcSweepOutcome.bytesReclaimed} is the data size of the blobs a run deleted, and deleting them
 * frees no disk: PostgreSQL keeps the space their rows occupied inside the chunk table's files and
 * never hands it to the filesystem. A store that a sweep emptied by 80% sat on exactly as full a
 * volume as before — which is how a "reclaimed 15 GiB" receipt and a full disk coexisted. The
 * library's {@code BlobCompaction} is the second half: it drains mostly-dead chunk segments into the
 * open one and drops their partitions, and dropping a partition is what unlinks files. These are its
 * figures, measured before and after, never estimated.
 *
 * <p><b>Two sizes, on purpose.</b> {@code blobTablesBytes*} is the blob tables' own on-disk size —
 * every chunk partition with its TOAST and index, plus {@code blob}, {@code blob_content} and {@code
 * blob_segment} — and is what compaction is accountable for. {@code databaseBytes*} is the whole
 * database as the volume sees it, which other tables move too, so the two need not agree.
 *
 * <p>The receipt carries this as {@code null} when no compaction figure exists: the run aborted
 * before deleting anything, nothing ran for the type, or compaction itself failed — which is logged
 * as a warning and never fails the sweep, because the deletes have already happened and their
 * receipt is the record of them.
 *
 * @param state what compaction did: {@code COMPACTED} when it looked at every closed segment, {@code
 *     UNSEGMENTED} on blob tables still in the pre-segment shape (nothing changed, the sizes still
 *     measured), {@code ALREADY_RUNNING} when another replica's sweep held the compaction lock
 * @param blobTablesBytesBefore the blob tables' on-disk bytes when compaction started
 * @param blobTablesBytesAfter the blob tables' on-disk bytes when it ended
 * @param databaseBytesBefore {@code pg_database_size} of the whole database when it started
 * @param databaseBytesAfter {@code pg_database_size} of the whole database when it ended
 * @param segmentsDrained closed segments whose live share was low enough that their live chunks were
 *     moved into the open segment
 * @param segmentsDropped partitions dropped, their files given back to the volume — drained ones and
 *     ones the deletes had already emptied
 * @param segmentsHeld segments drained or empty but not droppable on this run (an upload still
 *     staging in one, or the table lock not granted in time). Not a failure; the next run tries again
 * @param bytesMoved chunk bytes copied into the open segment while draining — the write cost paid
 *     for the disk returned
 */
public record GcDiskReport(
    String state,
    long blobTablesBytesBefore,
    long blobTablesBytesAfter,
    long databaseBytesBefore,
    long databaseBytesAfter,
    int segmentsDrained,
    int segmentsDropped,
    int segmentsHeld,
    long bytesMoved) {}
