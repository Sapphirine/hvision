package com.emadbarsoum.mapreduce;

import com.emadbarsoum.common.CommandParser;
import com.emadbarsoum.common.ImageHelper;
import com.emadbarsoum.common.MatData;
import com.emadbarsoum.common.MetadataParser;
import com.emadbarsoum.lib.BOWCluster;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.join.TupleWritable;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.bytedeco.javacpp.*;
import org.bytedeco.javacpp.opencv_core.*;
import org.bytedeco.javacpp.opencv_ml.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;

import static org.bytedeco.javacpp.opencv_core.*;
import static org.bytedeco.javacpp.opencv_highgui.*;

/**
 *
 * A MapReduce task, that takes an input a BOW xml file contains the BOW cluster, and a sequence file that is composed of a database of images
 * and a metadata containing the target label. ImageClassificationBOWTrainer will train using SVM on this data and return another sequence file,
 * in which each row contains an XML model for one of the label.
 *
 * Entry: com.emadbarsoum.mapreduce.ImageClassificationBOWTrainer
 *
 */
public class ImageClassificationBOWTrainer extends Configured implements Tool
{
    private static final Logger log = LoggerFactory.getLogger(ImageClassificationBOWTrainer.class);

    public static class ImageClassificationBOWTrainerMapper extends Mapper<Text, BytesWritable, IntWritable, TupleWritable>
    {
        @Override
        public void map(Text key, BytesWritable value, Context context) throws IOException,InterruptedException
        {
            context.setStatus("Status: map started");

            Configuration conf = context.getConfiguration();

            MetadataParser metadata = new MetadataParser(key.toString());
            metadata.parse();

            BOWCluster bowCluster = new BOWCluster(10);
            int labelId = metadata.getAsInt("labelid");
            int labelCount = metadata.getAsInt("label_count");

            context.setStatus("Status: Metadata parsed");

            URI[] uriPaths = context.getCacheFiles();
            if (uriPaths.length > 0)
            {
                boolean isRaw = metadata.has("type") && metadata.get("type").equals("raw");
                String bowClusterPath = uriPaths[0].getPath();
                IplImage image;

                bowCluster.load(bowClusterPath);
                context.setStatus("Status: BOW Cluster loaded");
                context.progress();

                if (isRaw)
                {
                    image = ImageHelper.createIplImageFromRawBytes(value.getBytes(), value.getLength(), metadata);

                    context.setStatus("Status: Image loaded");
                    context.progress();
                }
                else
                {
                    image = cvDecodeImage(cvMat(1, value.getLength(), CV_8UC1, new BytePointer(value.getBytes())));

                    context.setStatus("Status: Image loaded");
                    context.progress();
                }

                Mat imageMat = new Mat(image.asCvMat());
                bowCluster.compute(imageMat);
                MatData matData = MatData.create(bowCluster.getBowDescriptor());

                context.setStatus("Status: BOW descriptor Computed");
                context.progress();

                for (int i = 0; i < labelCount; ++i)
                {
                    if (i == labelId)
                    {
                        Writable[] writables =
                        {
                            new BytesWritable(matData.getBytes()),
                            new IntWritable(matData.rows()),
                            new IntWritable(matData.cols()),
                            new IntWritable(matData.type()),
                            new IntWritable(1)
                        };

                        context.write(new IntWritable(i), new TupleWritable(writables));
                    }
                    else
                    {
                        Writable[] writables =
                        {
                            new BytesWritable(matData.getBytes()),
                            new IntWritable(matData.rows()),
                            new IntWritable(matData.cols()),
                            new IntWritable(matData.type()),
                            new IntWritable(-1)
                        };

                        context.write(new IntWritable(i), new TupleWritable(writables));
                    }
                }

                context.setStatus("Status: map completed");

                // Releasing the images...
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

    public static class ImageClassificationBOWTrainerReducer extends Reducer<IntWritable, TupleWritable, Text, Text>
    {
        @Override
        public void reduce(IntWritable key, Iterable<TupleWritable> values, Context context) throws IOException, InterruptedException
        {
            int rowCount = 0;
            int entryCount = 0;

            for (TupleWritable val : values)
            {
                IntWritable rowWritable = (IntWritable)val.get(1);
                rowCount += rowWritable.get();
                entryCount++;
            }

            Mat x = new Mat();
            Mat labels = new Mat(entryCount, 1, CV_32FC1);

            int rowIndex = 0;
            for (TupleWritable val : values)
            {
                BytesWritable matWritable = (BytesWritable)val.get(0);
                int rows = ((IntWritable)val.get(1)).get();
                int cols = ((IntWritable)val.get(2)).get();
                int type = ((IntWritable)val.get(3)).get();
                int target = ((IntWritable)val.get(4)).get();

                Mat m = MatData.createMat(matWritable.getBytes(), rows, cols, type);

                x.push_back(m);
                labels.col(0).row(rowIndex).put(new Scalar((float)target));

                m.release();
                rowIndex++;
            }

            TermCriteria criteria = new TermCriteria(CV_TERMCRIT_ITER, 100, 1e-6);
            CvSVM svm = new CvSVM();
            CvSVMParams params = new CvSVMParams();
            params.svm_type(CvSVM.C_SVC);
            params.kernel_type(CvSVM.LINEAR);
            // params.gamma(1.0);
            params.term_crit(criteria.asCvTermCriteria());

            svm.train(x, labels, new Mat(), new Mat(), params);

            CvMemStorage storage = CvMemStorage.create();
            CvFileStorage fileStorage = CvFileStorage.open("*.xml", storage, CV_STORAGE_WRITE | CV_STORAGE_MEMORY);

            svm.write(fileStorage, "label_name");

            String svmXml = fileStorage.toString();
            context.write(new Text(""), new Text(svmXml));

            cvClearMemStorage(storage);
            cvReleaseFileStorage(fileStorage);
        }
    }

    @Override
    public final int run(final String[] args) throws Exception
    {
        Configuration conf = this.getConf();
        CommandParser parser = new CommandParser(args);
        parser.parse();

        Job job = Job.getInstance(conf, "Image Classification BOW Trainer");
        job.setJarByClass(ImageClassificationBOWTrainer.class);

        job.setMapperClass(ImageClassificationBOWTrainerMapper.class);
        job.setReducerClass(ImageClassificationBOWTrainerReducer.class);

        // Input Output format
        job.setInputFormatClass(SequenceFileInputFormat.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);

        job.setMapOutputKeyClass(IntWritable.class);
        job.setMapOutputValueClass(TupleWritable.class);

        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        FileInputFormat.addInputPath(job, new Path(parser.get("i")));
        FileOutputFormat.setOutputPath(job, new Path(parser.get("o")));

        // Use symbolic link "bowClusterFile" to support different platform formats
        // and protocols.
        job.addCacheFile(new URI(parser.get("c")));
        // job.addCacheFile(new URI(parser.get("c") + "#bowClusterFile"));

        boolean ret = job.waitForCompletion(true);
        return ret ? 0 : 1;
    }

    public static void main(String[] args) throws Exception
    {
        // Needed for SURF feature.
        Loader.load(opencv_nonfree.class);

        String[] nonOptional = {"i", "o", "c"};
        CommandParser parser = new CommandParser(args);
        if (!parser.parse()                ||
            (parser.getNumberOfArgs() < 3) ||
            !parser.has(nonOptional))
        {
            showUsage();
            System.exit(2);
        }

        ToolRunner.run(new Configuration(), new ImageClassificationBOWTrainer(), args);
    }

    private static void showUsage()
    {
        System.out.println("Usage: hvision icbowtrain -i <input path of the sequence file> -c <BOW cluster file> -o <output path for the result> [-m <hist or surf>]");
    }
}

