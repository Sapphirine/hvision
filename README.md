HVision
=======
Hadoop Vision (HVision) is a vision based platform on the top of Apache Hadoop MapReduce. It exposes a lot of Computer Vision and image processing algorithms from the command line or Java program. All mappers and MapReducers can be run locally or on Hadoop cluster.

Run HVision local
-----------------
Inspired by Mahout to run HVision local, you need to set HVISION_LOCAL to any value in your environment variables or in your ".bash_profile" file.

For example, put the below line in your .bash_profile file:

    export HVISION_LOCAL="Local"

Build HVision
-------------
HVision uses maven build system, Java version 1.7 and OpenCV, so make sure you install them first. And offcourse you will need Hadoop if you plan to run HVision on Hadoop, for local mode you don't need Hadoop. 

To build HVision clone the depot or download the zip file from GitHub into your machine, and run the following:

    mvn package -Dplatform.dependencies=true
    mvn install -Dplatform.dependencies=true

Setting "platform.dependencies" is extremely important in order to download JavaCV native dependencies.

####Build against specific Hadoop Version
By default, HVision build against Apache Hadoop 2.5.0, to change the version number. You can do it from command line or by updating the pom.xml file. Here the list of properties that control all the version information.

        <!-- Version information for HVision dependencies -->
        <java.version>1.7</java.version>
        <java.targetVersion>1.7</java.targetVersion>
        <junit.version>4.8.2</junit.version>
        <javacv.version>0.9</javacv.version>
        <javacpp.version>0.9</javacpp.version>
        <hadoop.version>2.5.0</hadoop.version>
        <mavenCompilerPlugin.version>3.1</mavenCompilerPlugin.version>
        <guava.version>14.0</guava.version>

Running HVision
---------------
To run most algorithms from the command line there is a bash script called "hvision" in the bin folder, that takes command as first arguments and the arguments of the command next. For this bash file to run on Hadoop, you will need to set the below environment variables:

    HADOOP_PREFIX set it to the root of your Hadoop folder.
    HADOOP_CONF_DIR set it to Hadoop folder that contains its configuration files.

Now, let's run a number of examples:

###Tools

Convert a folder of images into HVision compatible sequence file:

    ./bin/hvision iseq -i <image folder path> -o <output sequence file path>

Convert HVision sequence file back to images:

    ./bin/hvision idump -i <path to sequence file> -o <folder path of the result>

Create a thumbnails from a database of images stored in a sequence file:

    ./bin/hvision thumbnail -i <sequence file path> -o <folder path of the result> -size <thumbnail size in pixel>

Given a folder of images and number of cluster, generate the corresponding BOW cluster XML file.

    ./bin/hvision bowtrainer -i <input path to folder of images> -o <output path for model file> -c <number of cluster>

Given a folder of labeled images (label is the folder name), generate an HVision sequence file with the label information in the metadata. 
    
    ./bin/hvision iseqlab -i <input path to folder of images> -o <output path for sequence file> [-raw]

###Mappers only jobs

Find faces on a database of images stored in a sequence file:

    ./bin/hvision findfaces -i <sequence file path> -o <folder path of the result> -m < model XML path i.e. haarcascade_frontalface_alt.xml>

Blur all images in a database of images stored in a sequence file using Gaussian filter:

    ./bin/hvision gaussian -i <sequence file path> -o <folder path of the result> -size <kernel size> -sigma <gaussian sigma>

Blur all images in a database of images stored in a sequence file using Median filter:

    ./bin/hvision median -i <sequence file path> -o <folder path of the result> -size <kernel size>

Convert all images in a sequence file to a gray images:

    ./bin/hvision color2gray -i <input path of the sequence file> -o <output path for sequence file>

Dilate all images in a sequence file

    ./bin/hvision dilate -i <input path of the sequence file> -o <output path for sequence file>

Erode all images in a sequence file

    ./bin/hvision erode -i <input path of the sequence file> -o <output path for sequence file>

###MapReduce jobs

Given an HVision sequence file of images and a query image, sort all the images from most similar to least similar to the query image. Default is using histogram, but you can specify hist for histogram or surf for SURF.

    ./bin/hvision imagesearch -i <input path of the sequence file> -q <query image> -o <output path for the result> [-m <hist or surf>]

Similar to imagesearch but with total sort, so with more than one reducer you can concatenate the multiple sequence files while preserve the global sort.

    ./bin/hvision imagesearchtotal -i <input path of the sequence file> -q <query image> -o <output path for the result> [-m <hist or surf>]

Given an HVision sequence file of images and a model XML file (i.e. haarcascade_frontalface_alt.xml), return the number of images that have 0, 1, 2, 3 or more faces.

    ./bin/hvision facestat -i <input path of the sequence file> -o <output path for sequence file> -m <model path>

Given an HVision sequence file of labeled images and a BOW cluster file, return an SVM model for each label.

    ./bin/hvision icbowtrain -i <input path of the sequence file> -cf <BOW cluster file> -o <output path for the result> [-c <cluster count>]

###Set Hadoop arguments
To change Hadoop parameters such as the number of reducers, you need to specify the argument immediately after the command and before the command arguments. The reason behind that is a limitation of Hadoop general parser.

The below call image search with 2 reducers:

    ./bin/hvision imagesearch -Dmapreduce.job.reduces=2 -i <input path of the sequence file> -q <query image> -o <output path for the result> [-m <hist or surf>]
