package com.emadbarsoum.lib;

import com.emadbarsoum.common.ImageHelper;
import org.bytedeco.javacpp.opencv_core.*;
import org.bytedeco.javacpp.opencv_features2d.*;

import static org.bytedeco.javacpp.opencv_core.*;

/**
 * Bag of Word cluster module.
 */
public class BOWCluster
{
    private TermCriteria termCriteria = new TermCriteria(CV_TERMCRIT_ITER, 10, 0.001);
    private BOWImgDescriptorExtractor bowDescriptorExtractor;
    private BOWKMeansTrainer bowTrainer;
    private FeatureDetector featureDetector = FeatureDetector.create("SURF");
    private DescriptorExtractor descriptorExtractor = DescriptorExtractor.create("SURF");
    private BFMatcher matcher = new BFMatcher();
    private Mat vocabulary;
    private Mat bowDescriptor;

    public Mat getVocabulary()
    {
        return this.vocabulary;
    }

    public void setVocabulary(Mat vocabulary)
    {
        this.vocabulary = vocabulary;
    }

    public Mat getBowDescriptor()
    {
        return this.bowDescriptor;
    }

    public BOWCluster(int clusterCount)
    {
        this.bowTrainer = new BOWKMeansTrainer(clusterCount, this.termCriteria, 1, 2);
        this.bowDescriptorExtractor = new BOWImgDescriptorExtractor(descriptorExtractor, matcher);
    }

    public void compute(Mat image)
    {
        this.bowDescriptor = new Mat();
        KeyPoint keypoints = new KeyPoint(null);

        this.featureDetector.detect(image, keypoints);
        this.bowDescriptorExtractor.compute(image, keypoints, this.bowDescriptor);
    }

    public void cluster()
    {
        this.vocabulary = this.bowTrainer.cluster();
        this.bowDescriptorExtractor.setVocabulary(this.vocabulary);
    }

    public void add(Mat image)
    {
        Mat descriptor = new Mat();
        KeyPoint keypoints = new KeyPoint(null);

        this.featureDetector.detect(image, keypoints);
        this.descriptorExtractor.compute(image, keypoints, descriptor);

        this.bowTrainer.add(descriptor);
    }

    public void clear()
    {
        this.bowTrainer.clear();
    }

    public void Save(String path) throws IllegalStateException
    {
        if (this.vocabulary == null)
        {
            throw new IllegalStateException("You need to call cluster() before saving the result.");
        }

        ImageHelper.serializeMat(BOWCluster.class.getName(), this.vocabulary, path);
    }

    public void Load(String path)
    {
        this.vocabulary = ImageHelper.deserializeMat(BOWCluster.class.getName(), path);
    }
}
