package com.example.makerecg;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * A utility class to provide an interface from ADSampleFrame objects into our synchronization
 * framework.
 */
public class ECGContentUtilities {

    private Context mCtx;
    private static ECGContentUtilities mInstance = null;

    public static ECGContentUtilities getInstance(Context ctx) {
        if (mInstance == null) {
            mInstance = new ECGContentUtilities(ctx);
        }
        return mInstance;
    }

    public ECGContentUtilities(Context ctx) {
        mCtx = ctx;
    }

    /**
     * Add a frame to the database.
     *
     * @return rowId or -1 if failed
     */
    public Uri addFrame(ADSampleFrame frame) {
        ContentValues initialValues = new ContentValues();
        short [] samples = frame.getSamples();
        byte [] s = new byte[(int)(frame.getSampleCount()*2)];

        int j = 0;
        for(int i=0; i<frame.getSampleCount(); ++i) {
            s[j] = (byte)((samples[i] >> 8) & 0xff);
            s[j+1] = (byte)(samples[i] & 0xff);
            j+=2;
        }

        initialValues.put(ECGContentProvider._ID,
                frame.getDatasetUuid().toString() + frame.getStartTimestamp());
        initialValues.put(ECGContentProvider.COLUMN_SAMPLE_ID,
                frame.getDatasetUuid().toString());
        initialValues.put(ECGContentProvider.COLUMN_SAMPLE_DATE,
                frame.getDatasetDate());
        initialValues.put(ECGContentProvider.COLUMN_SAMPLE_COUNT,
                frame.getSampleCount());
        initialValues.put(ECGContentProvider.COLUMN_SAMPLES, s);
        initialValues.put(ECGContentProvider.COLUMN_START_TIME_MS,
                frame.getStartTimestamp());
        initialValues.put(ECGContentProvider.COLUMN_END_TIME_MS,
                frame.getEndTimestamp());
        initialValues.put(ECGContentProvider.COLUMN_UPLOADED_TS,
                (String) null);

        return mCtx.getContentResolver().insert(
                ECGContentProvider.CONTENT_URI, initialValues);
    }

    /**
     * Gather a list of any records that might be waiting for upload.
     */
    public List<ADSampleFrame> uploadPendingFrames(int maxSyncSize) {

        List<ADSampleFrame> result = new LinkedList<ADSampleFrame>();
        int count = 0;
        Uri sampleFrames = Uri.parse(ECGContentProvider.URL);

        Cursor c = mCtx.getContentResolver().query(
                sampleFrames,
                new String[]{
                        ECGContentProvider._ID,
                        ECGContentProvider.COLUMN_SAMPLE_ID,
                        ECGContentProvider.COLUMN_SAMPLE_DATE,
                        ECGContentProvider.COLUMN_SAMPLE_COUNT,
                        ECGContentProvider.COLUMN_SAMPLES,
                        ECGContentProvider.COLUMN_START_TIME_MS,
                        ECGContentProvider.COLUMN_END_TIME_MS
                },
                ECGContentProvider.COLUMN_UPLOADED_TS + " IS NULL",
                null,
                ECGContentProvider._ID);

        if (c.moveToFirst()) {
            do{
                ADSampleFrame frame = new ADSampleFrame(
                        UUID.fromString(c.getString(c.getColumnIndex(ECGContentProvider.COLUMN_SAMPLE_ID))),
                        c.getString(c.getColumnIndex(ECGContentProvider.COLUMN_SAMPLE_DATE)),
                        c.getLong(c.getColumnIndex(ECGContentProvider.COLUMN_START_TIME_MS)),
                        c.getLong(c.getColumnIndex(ECGContentProvider.COLUMN_END_TIME_MS)),
                        c.getShort(c.getColumnIndex(ECGContentProvider.COLUMN_SAMPLE_COUNT)),
                        blobToShortArray(c.getBlob(c.getColumnIndex(ECGContentProvider.COLUMN_SAMPLES)),
                                c.getShort(c.getColumnIndex(ECGContentProvider.COLUMN_SAMPLE_COUNT))));

                // Send it
                result.add(frame);

                ++ count;

            } while (c.moveToNext() && count < maxSyncSize);
        }

        c.close();

        return result;
    }

    public void markAsUploaded(List<ADSampleFrame> frames) {
        String URL = null;
        int count = 0;
        ContentValues values = new ContentValues();
        values.put(ECGContentProvider.COLUMN_UPLOADED_TS,
                Utilities.getISO8601StringForDate(new Date()));

        for (ADSampleFrame frame: frames) {

            URL = ECGContentProvider.URL + "/" +
                    frame.getDatasetUuid().toString() + "." +
                    frame.getStartTimestamp();

            Uri sampleFrames = Uri.parse(URL);

            mCtx.getContentResolver().update(sampleFrames, values, null, null);
                   /* ECGContentProvider.COLUMN_SAMPLE_ID + " = ? and " +
                            ECGContentProvider.COLUMN_START_TIME_MS + " = ?",
                    new String[]{frame.getDatasetUuid().toString(),
                            "" + frame.getStartTimestamp()}); */
        }
    }

    private short[] blobToShortArray(byte[] bytes, int length) {
        short [] samples = new short[length];
        int j = 0;
        for(int i=0; i<length; ++i) {
            samples[i] = (short) (((bytes[j] << 8) & 0xff00) | (bytes[j+1] & 0xff));
            j+=2;
        }
        return samples;
    }

    public void startDatabaseWriterThread() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    while (true) {
                        writeSampleBlocks();
                        Thread.sleep(1000);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    // no action
                }
            }
        }).start();
    }

    /*
     * Save all pending sample blocks to the database
     */
    private void writeSampleBlocks() throws IOException {

        ADSampleQueue queue = ADSampleQueue.getSingletonObject();
        Iterator<ADSampleFrame> it = queue.getWriteableSampleBlocks().iterator();
        ADSampleFrame frame;

        while(it.hasNext()) {
            frame = it.next();
            addFrame(frame);
            frame.setDirty(false);
            it.remove();
        }
    }

}