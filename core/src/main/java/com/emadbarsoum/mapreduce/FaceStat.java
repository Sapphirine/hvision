package com.emadbarsoum.mapreduce;

import com.emadbarsoum.common.CommandParser;
import com.emadbarsoum.common.ImageHelper;
import com.emadbarsoum.common.MetadataParser;
import com.emadbarsoum.lib.FaceDetection;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.opencv_core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URI;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_highgui.*;

/**
 * FaceStat is a Hadoop MapReduce task that find all faces in a given Sequence File of Images
 * and output how many image have 0, 1, 2, 3 or more faces.
 *
 * Entry: com.emadbarsoum.mapreduce.FaceStat
 */
public class FaceStat extends Configured implements Tool
{
    private static final Logger log = LoggerFactory.getLogger(FaceStat.class);

    public static class FaceStatMapper extends Mapper<Text, BytesWritable, IntWritable, IntWritable>
    {
        private final static IntWritable one = new IntWritable(1);

        @Override
        public void map(Text key, BytesWritable value, Context context) throws IOException,InterruptedException
        {
            context.setStatus("Status: map started");

            Configuration conf = context.getConfiguration();

            MetadataParser metadata = new MetadataParser(key.toString());
            metadata.parse();

            context.setStatus("Status: Metadata parsed");

            URI[] uriPaths = context.getCacheFiles();
            if (uriPaths.length > 0)
            {
                boolean isRaw = metadata.has("type") && metadata.get("type").equals("raw");
                IplImage image;
                FaceDetection detector = new FaceDetection();

                detector.setModel("faceModelFile");
                if (isRaw)
                {
                    int width = metadata.getAsInt("width");
                    int height = metadata.getAsInt("height");
                    int channelCount = metadata.getAsInt("channel_count");
                    int depth =  metadata.getAsInt("depth");

                    image = ImageHelper.createIplImageFromRawBytes(value.getBytes(),
                        value.getLength(),
                        width,
                        height,
                        channelCount,
                        depth);

                    context.setStatus("Status: Image loaded");
                    context.progress();
                }
                else
                {
                    image = cvDecodeImage(cvMat(1, value.getLength(), CV_8UC1, new BytePointer(value.getBytes())));

                    context.setStatus("Status: Image loaded");
                    context.progress();
                }

                try
                {
                    detector.Detect(image, context);

                    // Count 0 to 3 people, more than that will be bucket into Crowd.
                    if (detector.count() < 4)
                    {
                        context.write(new IntWritable(detector.count()), one);
                    }
                    else
                    {
                        context.write(new IntWritable(100), one);
                    }
                }
                catch (Exception e)
                {
                    //TODO: log error.
                }

                context.setStatus("Status: map completed");

                if (isRaw)
                {
                    image.release();
                }
                else
                {
                    cvReleaseImage(image);
                }
            }
        }
    }

    public static class FaceStatReducer extends Reducer<IntWritable, IntWritable, Text, IntWritable>
    {
        @Override
        public void reduce(IntWritable key, Iterable<IntWritable> values, Context context) throws IOException, InterruptedException
        {
            String name;
            switch (key.get())
            {
                case 0:
                    name = "Number of images with no person";
                    break;
                case 1:
                    name = "Number of images with one person";
                    break;
                case 2:
                    name = "Number of images with two persons";
                    break;
                case 3:
                    name = "Number of images with three persons";
                    break;
                default:
                    name = "Number of images with crowd";
                    break;
            }

            int sum = 0;
            for (IntWritable val : values)
            {
                sum += val.get();
            }

            context.write(new Text(name), new IntWritable(sum));
        }
    }

    @Override
    public final int run(final String[] args) throws Exception
    {
        Configuration conf = this.getConf();
        CommandParser parser = new CommandParser(args);
        parser.parse();

        Job job = Job.getInstance(conf, "Face Stat");
        job.setJarByClass(ImageSearch.class);

        job.setMapperClass(FaceStatMapper.class);
        job.setReducerClass(FaceStatReducer.class);

        // Input Output format
        job.setInputFormatClass(SequenceFileInputFormat.class);

        job.setMapOutputKeyClass(IntWritable.class);
        job.setMapOutputValueClass(IntWritable.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(IntWritable.class);

        FileInputFormat.addInputPath(job, new Path(parser.get("i")));
        FileOutputFormat.setOutputPath(job, new Path(parser.get("o")));

        // Add the model XML file to the distributed cache.
        // Use symbolic link "faceModelFile" to support different platform formats
        // and protocols.
        job.addCacheFile(new URI(parser.get("m") + "#faceModelFile"));

        boolean ret = job.waitForCompletion(true);
        return ret ? 0 : 1;
    }

    public static void main(String[] args) throws Exception
    {
        String[] nonOptional = {"i", "o", "m"};
        CommandParser parser = new CommandParser(args);
        if (!parser.parse()                ||
            (parser.getNumberOfArgs() < 3) ||
            !parser.has(nonOptional))
        {
            showUsage();
            System.exit(2);
        }

        ToolRunner.run(new Configuration(), new FaceStat(), args);
    }

    private static void showUsage()
    {
        System.out.println("Usage: hvision facestat -i <input path of the sequence file> -o <output path for sequence file> -m <model path>");
    }
}
