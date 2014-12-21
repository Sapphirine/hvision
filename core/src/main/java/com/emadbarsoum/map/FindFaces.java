package com.emadbarsoum.map;

import com.emadbarsoum.common.CommandParser;
import com.emadbarsoum.common.ImageHelper;
import com.emadbarsoum.common.MetadataParser;
import com.emadbarsoum.lib.FaceDetection;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.bytedeco.javacpp.BytePointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_highgui.*;

/**
 * FindFaces is a Hadoop Map task that find all faces in a given Sequence File of Images
 * and output the result into another Sequence file.
 *
 * FindFaces will update Sequence File metadata with the number of faces.
 *
 * Entry: com.emadbarsoum.map.FindFaces
 */
public class FindFaces extends Configured implements Tool
{
    private static final Logger log = LoggerFactory.getLogger(FindFaces.class);

    public static class FindFacesMapper extends Mapper<Text, BytesWritable, Text, BytesWritable>
    {
        @Override
        public void map(Text key, BytesWritable value, Context context) throws IOException,InterruptedException
        {
            Configuration conf = context.getConfiguration();

            MetadataParser metadata = new MetadataParser(key.toString());
            metadata.parse();

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

                    if (detector.count() > 0)
                    {
                        CvMat imageMat = cvEncodeImage("." + metadata.get("ext"), detector.getResultImage());

                        // Write the result...
                        byte[] data = new byte[imageMat.size()];
                        imageMat.getByteBuffer().get(data);

                        // The result stored as compressed.
                        metadata.remove("type");

                        // Store face count.
                        metadata.put("facecount", detector.count());

                        context.write(new Text(metadata.toMetadata()), new BytesWritable(data));

                        cvReleaseMat(imageMat);
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

    @Override
    public final int run(final String[] args) throws Exception
    {
        Configuration conf = this.getConf();
        CommandParser parser = new CommandParser(args);
        parser.parse();

        Job job = Job.getInstance(conf, "Find Faces");
        job.setJarByClass(Gaussian.class);

        job.setMapperClass(FindFacesMapper.class);
        job.setNumReduceTasks(0);

        // Input Output format
        job.setInputFormatClass(SequenceFileInputFormat.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(BytesWritable.class);

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
            !(parser.has(nonOptional)))
        {
            showUsage();
            System.exit(2);
        }

        ToolRunner.run(new Configuration(), new FindFaces(), args);
    }

    private static void showUsage()
    {
        System.out.println("Usage: hvision findfaces -i <input path of the sequence file> -o <output path for sequence file> -m <model path>");
    }
}
