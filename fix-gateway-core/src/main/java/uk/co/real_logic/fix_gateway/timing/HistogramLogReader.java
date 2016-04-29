/*
 * Copyright 2015-2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.timing;

import org.HdrHistogram.Histogram;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.LangUtil;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.BackoffIdleStrategy;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.*;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.agrona.IoUtil.checkFileExists;

/**
 * Reader that logs and prints out the latency histograms generated by the
 * {@link HistogramLogWriter}.
 */
public class HistogramLogReader implements AutoCloseable
{
    public static void main(String[] args) throws IOException
    {
        if (args.length != 1)
        {
            System.err.println("Usage: HistogramLogReader <logFile>");
            System.err.println("Where <logFile> is the path to histogram log file");
            System.exit(-1);
        }

        final String path = args[0];
        final File file = new File(path);
        final double scalingFactor = MICROSECONDS.toNanos(1);
        final BackoffIdleStrategy idleStrategy = new BackoffIdleStrategy(0, 0, MILLISECONDS.toNanos(1),
            MINUTES.toNanos(1));

        try (final HistogramLogReader logReader = new HistogramLogReader(file))
        {
            while (true)
            {
                final int sampleCount = logReader.read((recordedAtTime, name, histogram) ->
                    prettyPrint(recordedAtTime, histogram, name, scalingFactor));

                idleStrategy.idle(sampleCount);
            }
        }
    }

    private final Int2ObjectHashMap<String> idToName = new Int2ObjectHashMap<>();

    private FileChannel channel;
    private MappedByteBuffer buffer;

    public HistogramLogReader(final File file)
    {
        openFile(file);
        readHeader();
    }

    private void openFile(final File file)
    {
        checkFileExists(file, file.getName());

        try
        {
            final RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
            channel = randomAccessFile.getChannel();
            map(channel.size());
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
    }

    private void remapIfExpanded() throws IOException
    {
        final long size = channel.size();
        if (size != buffer.capacity())
        {
            final int previousPosition = buffer.position();
            map(size);
            buffer.position(previousPosition);
        }
    }

    private void map(final long size) throws IOException
    {
        buffer = channel.map(READ_ONLY, 0, size);
    }

    private void readHeader()
    {
        final int timerCount = buffer.getInt();
        for (int i = 0; i < timerCount; i++)
        {
            final int id = buffer.getInt();
            final byte[] nameBytes = new byte[buffer.getInt()];
            buffer.get(nameBytes);
            final String name = new String(nameBytes, UTF_8);
            idToName.put(id, name);
        }
    }

    public int read(final HistogramLogHandler handler) throws IOException
    {
        remapIfExpanded();

        final int timerCount = idToName.size();
        int samplesRead = 0;
        while (true)
        {
            if (buffer.remaining() < SIZE_OF_LONG)
            {
                return samplesRead;
            }

            buffer.mark();
            final long timeStamp = buffer.getLong();
            if (timeStamp == 0)
            {
                buffer.reset();
                return samplesRead;
            }

            for (int i = 0; i < timerCount; i++)
            {
                final int id = buffer.getInt();
                final String name = idToName.get(id);
                final Histogram histogram = Histogram.decodeFromByteBuffer(buffer, 0);
                handler.onHistogram(timeStamp, name, histogram);
            }
            samplesRead++;
        }
    }

    public void close()
    {
        IoUtil.unmap(buffer);
        CloseHelper.close(channel);
    }

    public static void prettyPrint(
        final long timestampInMs,
        final Histogram histogram,
        final String name,
        final double scalingFactor)
    {
        System.out.printf(
            "%s Histogram @ %dmillis\n" +
            "----------\n" +
            "Mean: %G\n" +
            "1:    %G\n" +
            "50:   %G\n" +
            "90:   %G\n" +
            "99:   %G\n" +
            "99.9: %G\n" +
            "100:  %G\n" +
            "----------\n",

            name,
            timestampInMs,
            histogram.getMean() / scalingFactor,
            scaledPercentile(histogram, scalingFactor, 1),
            scaledPercentile(histogram, scalingFactor, 50),
            scaledPercentile(histogram, scalingFactor, 90),
            scaledPercentile(histogram, scalingFactor, 99),
            scaledPercentile(histogram, scalingFactor, 99.9),
            scaledPercentile(histogram, scalingFactor, 100));
    }

    private static double scaledPercentile(final Histogram histogram,
                                           final double scalingFactor,
                                           final double percentile)
    {
        return histogram.getValueAtPercentile(percentile) / scalingFactor;
    }
}
