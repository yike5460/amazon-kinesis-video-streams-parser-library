/*
Copyright 2017-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License").
You may not use this file except in compliance with the License.
A copy of the License is located at

   http://aws.amazon.com/apache2.0/

or in the "license" file accompanying this file.
This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and limitations under the License.
*/
package com.amazonaws.kinesisvideo.parser.examples.lambda;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.client.mediasource.CameraMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.java.client.KinesisVideoJavaClientFactory;
import com.amazonaws.kinesisvideo.parser.examples.BoundingBoxImagePanel;
import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.mkv.FrameProcessException;
import com.amazonaws.kinesisvideo.parser.rekognition.pojo.RekognizedOutput;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadata;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.kinesisvideo.parser.utilities.H264FrameDecoder;
import com.amazonaws.kinesisvideo.parser.utilities.H264FrameEncoder;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTrackMetadata;
import com.amazonaws.kinesisvideo.parser.utilities.ProducerStreamUtil;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import com.amazonaws.regions.Regions;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;

import javax.imageio.*;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.AmazonClientException;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.transfer.TransferManager;

import java.net.URL;

@Slf4j
public class H264FrameProcessor implements FrameVisitor.FrameProcessor {

    private static final int MILLIS_IN_SEC = 1000;
    private static final int OFFSET_DELTA_THRESHOLD = 10;

    private final BoundingBoxImagePanel boundingBoxImagePanel;
    private final Regions regionName;
    private RekognizedOutput currentRekognizedOutput = null;
    private H264FrameEncoder h264Encoder;
    private H264FrameDecoder h264Decoder;
    private KVSMediaSource KVSMediaSource;
    private boolean isKVSProducerInitialized = false;
    private boolean isEncoderInitialized = false;
    private boolean isFacialFounded = false;
    private final AWSCredentialsProvider credentialsProvider;
    private final String outputKvsStreamName;
    @Setter
    private List<RekognizedOutput> rekognizedOutputs;
    @Setter
    private int frameBitRate = 1024;
    private int frameNo = 0;
    private int currentWidth = 0;
    private int currentHeight = 0;
    private long keyFrameTimecode;

    private H264FrameProcessor(final AWSCredentialsProvider credentialsProvider,
                               final String outputKvsStreamName,
                               final Regions regionName) {
        this.boundingBoxImagePanel = new BoundingBoxImagePanel();
        this.credentialsProvider = credentialsProvider;
        this.outputKvsStreamName = outputKvsStreamName;
        this.regionName = regionName;
        this.h264Decoder = new H264FrameDecoder();
    }

    private void initializeKinesisVideoProducer(final byte[] cpd) {
        try {
            log.info("Initializing KVS Producer with stream name {} and region : {}",
                    outputKvsStreamName, regionName);
            final KinesisVideoClient kinesisVideoClient = KinesisVideoJavaClientFactory
                    .createKinesisVideoClient(regionName, credentialsProvider);
            final CameraMediaSourceConfiguration configuration =
                    new CameraMediaSourceConfiguration.Builder()
                            .withFrameRate(30)
                            .withRetentionPeriodInHours(1)
                            .withCameraId("/dev/video0")
                            .withIsEncoderHardwareAccelerated(false)
                            .withEncodingMimeType("video/avc")
                            .withNalAdaptationFlags(StreamInfo.NalAdaptationFlags.NAL_ADAPTATION_ANNEXB_NALS)
                            .withIsAbsoluteTimecode(true)
                            .withEncodingBitRate(200000)
                            .withHorizontalResolution(640)
                            .withVerticalResolution(480)
                            .withCodecPrivateData(cpd)
                            .withIsAbsoluteTimecode(true)
                            .build();
            this.KVSMediaSource = new KVSMediaSource(
                    ProducerStreamUtil.toStreamInfo(outputKvsStreamName, configuration));
            this.KVSMediaSource.configure(configuration);
            // register media source with Kinesis Video Client
            kinesisVideoClient.registerMediaSource(KVSMediaSource);
        } catch (final KinesisVideoException e) {
            log.error("Exception while initialize KVS Producer !", e);
        }
    }

    public void resetEncoder() {

        // Reset frame count for this fragment
        if (this.isEncoderInitialized) {
            this.frameNo = 0;
            this.h264Encoder.setFrameNumber(frameNo);

        } else {
            throw new IllegalStateException("Encoder not initialized !");
        }
    }

    public static H264FrameProcessor create(final AWSCredentialsProvider credentialsProvider,
                                            final String rekognizedStreamName,
                                            final Regions regionName) {
        return new H264FrameProcessor(credentialsProvider, rekognizedStreamName, regionName);
    }

    /**
     * Process Rekognized outputs for each rekognized output. For each kinesis event record i.e for each
     * fragment number create a call getMediaForFragmentList, parse fragments, decode frame, draw bounding box,
     * encode frame, call KVS PutFrame.
     */
    @Override
    public void process(final Frame frame, final MkvTrackMetadata trackMetadata,
                        final Optional<FragmentMetadata> fragmentMetadata) throws FrameProcessException {
        final double d = Math.random();
        // Sample rate 1:100
        if ((rekognizedOutputs != null) && (1 == (int) (d*100))) {

            checkState(fragmentMetadata.isPresent(), "FragmentMetadata should be present !");

            // Decode H264 frame
            final BufferedImage decodedFrame = h264Decoder.decodeH264Frame(frame, trackMetadata);
            log.debug("Decoded frame : {} with timecode : {} and fragment metadata : {}",
                    frameNo, frame.getTimeCode(), fragmentMetadata.get());

            // Get Rekognition results for this fragment number
            final Optional<RekognizedOutput> rekognizedOutput = findRekognizedOutputForFrame(frame, fragmentMetadata);

            // Render frame with bounding box
            final BufferedImage compositeFrame = renderFrame(decodedFrame, rekognizedOutput);
            try {
                //ImageIO.write(compositeFrame, "png", new File(String.format("frame-capture-%s.png", UUID.randomUUID())));
                ImageIO.write(compositeFrame, "png", new File("/tmp/frame-capture.png"));
             } catch (IOException e) {
                log.warn("Couldn't convert to a PNG", e);
            }

            /*Regions clientRegion = Regions.AP_NORTHEAST_1;
            String bucketName = "delete-me-jack";
            String stringObjKeyName = "frame-capture.png";
            String fileObjKeyName = "frame-capture.png";
            String fileName = "/tmp";

            try {
                //This code expects that you have AWS credentials set up per:
                // https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/setup-credentials.html
                AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                        .withRegion(clientRegion)
                        .build();

                // Upload a text string as a new object.
                s3Client.putObject(bucketName, stringObjKeyName, "Uploaded String Object");

                // Upload a file as a new object with ContentType and title specified.
                PutObjectRequest request = new PutObjectRequest(bucketName, fileObjKeyName, new File(fileName));
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentType("plain/image");
                metadata.addUserMetadata("x-amz-meta-title", "reko result snapshot");
                request.setMetadata(metadata);
                s3Client.putObject(request);
            } catch (AmazonServiceException e) {
                // The call was transmitted successfully, but Amazon S3 couldn't process
                // it, so it returned an error response.
                e.printStackTrace();
            } catch (SdkClientException e) {
                // Amazon S3 couldn't be contacted for a response, or the client
                // couldn't parse the response from Amazon S3.
                e.printStackTrace();
            }*/

            String bucketName = "delete-me-jack";
            String stringObjKeyName = "faceSnapshot";
            if (isFacialFounded) {
                try {
                    AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                            .withRegion(Regions.AP_NORTHEAST_1)
                            .build();
                    /*
                    try to use InputStream to avoid S3 deny error
                    (com.amazonaws.services.s3.model.AmazonS3Exception: Access Denied (Service: Amazon S3; Status Code: 403; Error Code: AccessDenied; ...)
                    code like: InputStream is = file.getInputStream();
                    */
                    s3Client.putObject(bucketName, stringObjKeyName, new File("/tmp/frame-capture.png"));

                    //String bucketPath = bucketName + "/facial" ;
                    s3Client.putObject(new PutObjectRequest(bucketName, stringObjKeyName, new File("/tmp/frame-capture.png"))
                            .withCannedAcl(CannedAccessControlList.PublicRead));
                    GeneratePresignedUrlRequest urlRequest = new GeneratePresignedUrlRequest(bucketName, stringObjKeyName);
                    URL url = s3Client.generatePresignedUrl(urlRequest);
                    //return url.toString();
                    log.debug("pre-signed url : {}", url.toString());
                } catch (AmazonServiceException ase) {
                    ase.printStackTrace();
                } catch (AmazonClientException ace) {
                    ace.printStackTrace();
                }
            }
            /* bypass video encode to aviode computational resource consumption in lambda// Encode to H264 frame
            final EncodedFrame encodedH264Frame = encodeH264Frame(compositeFrame);
            encodedH264Frame.setTimeCode(fragmentMetadata.get().getProducerSideTimestampMillis() + frame.getTimeCode());
            log.debug("Encoded frame : {} with timecode : {}", frameNo, encodedH264Frame.getTimeCode());

            // Call PutFrame for processed encodedFrame.
            putFrame(encodedH264Frame);*/
            frameNo++;

        } else {
            log.warn("Rekognition output is empty");
        }
    }

    private void putFrame(final EncodedFrame encodedH264Frame) {
        if (!isKVSProducerInitialized) {
            log.info("Initializing JNI...");
            initializeKinesisVideoProducer(encodedH264Frame.getCpd().array());
            isKVSProducerInitialized = true;
        }
        KVSMediaSource.putFrameData(encodedH264Frame);
        log.debug("PutFrame successful for frame no : {}", frameNo);
    }

    private EncodedFrame encodeH264Frame(final BufferedImage bufferedImage) {
        try {
            initializeEncoder(bufferedImage);
            return h264Encoder.encodeFrame(bufferedImage);
        } catch (final Exception e) {
            throw new RuntimeException("Unable to encode the bufferedImage !", e);
        }
    }

    private void initializeEncoder(final BufferedImage bufferedImage) {
        // Initialize the encoder if it's not initialized or if the current frame resolution changes from previous one.
        if (!isEncoderInitialized ||
                (currentWidth != bufferedImage.getWidth() || currentHeight != bufferedImage.getHeight())) {
            this.h264Encoder = new H264FrameEncoder(bufferedImage.getWidth(), bufferedImage.getHeight(),
                    frameBitRate);
            this.isEncoderInitialized = true;
            this.currentWidth = bufferedImage.getWidth();
            this.currentHeight = bufferedImage.getHeight();
        }
    }

    private Optional<RekognizedOutput> findRekognizedOutputForFrame(final Frame frame,
                                                                    final Optional<FragmentMetadata> fragmentMetadata) {

        Optional<RekognizedOutput> rekognizedOutput = Optional.empty();
        if (fragmentMetadata.isPresent()) {
            final String fragmentNumber = fragmentMetadata.get().getFragmentNumberString();

            // Currently Rekognition samples frames and calculates the frame offset from the fragment start time.
            // So, in order to match with rekognition results, we have to compute the same frame offset from the
            // beginning of the fragments.
            if (frame.isKeyFrame()) {
                keyFrameTimecode = frame.getTimeCode();
                log.debug("Key frame timecode : {}", keyFrameTimecode);
            }
            final long frameOffset = (frame.getTimeCode() > keyFrameTimecode)
                    ? frame.getTimeCode() - keyFrameTimecode : 0;
            log.debug("Current Fragment Number : {} Computed Frame offset : {}", fragmentNumber, frameOffset);
            if (log.isDebugEnabled()) {
                this.rekognizedOutputs
                        .forEach(p -> log.debug("frameOffsetInSeconds from Rekognition : {}",
                                p.getFrameOffsetInSeconds()));
            }

            // Check whether the computed offset matches the rekognized output frame offset. Rekognition
            // output is in seconds whereas the frame offset is calculated in milliseconds.
            // NOTE: Rekognition frame offset doesn't exactly match with the computed offset below. So
            // take the closest one possible within 10ms delta.
            rekognizedOutput = this.rekognizedOutputs.stream()
                    .filter(p -> isOffsetDeltaWithinThreshold(frameOffset, p))
                    .findFirst();

            // Remove from the index once the RekognizedOutput is processed. Else it would increase the memory
            // footprint and blow up the JVM.
            if (rekognizedOutput.isPresent()) {
                log.debug("Computed offset matched with retrieved offset. Delta : {}",
                        Math.abs(frameOffset - (rekognizedOutput.get().getFrameOffsetInSeconds() * MILLIS_IN_SEC)));

                if (this.rekognizedOutputs.isEmpty()) {
                    log.debug("All frames processed for this fragment number : {}", fragmentNumber);
                }
            }
        }
        return rekognizedOutput;
    }



    private boolean isOffsetDeltaWithinThreshold(final long frameOffset, final RekognizedOutput output) {
        return Math.abs(frameOffset - (output.getFrameOffsetInSeconds() * MILLIS_IN_SEC)) <= OFFSET_DELTA_THRESHOLD;
    }

    @SuppressWarnings("Duplicates")
    private BufferedImage renderFrame(final BufferedImage bufferedImage, final Optional<RekognizedOutput> rekognizedOutput) {
        if (rekognizedOutput.isPresent()) {
            log.debug("Rendering Rekognized sampled frame...");
            boundingBoxImagePanel.processRekognitionOutput(bufferedImage.createGraphics(), bufferedImage.getWidth(),
                    bufferedImage.getHeight(), rekognizedOutput.get());
            currentRekognizedOutput = rekognizedOutput.get();
            isFacialFounded = true;
        } else if (currentRekognizedOutput != null) {
            log.debug("Rendering non-sampled frame with previous rekognized results...");
            boundingBoxImagePanel.processRekognitionOutput(bufferedImage.createGraphics(), bufferedImage.getWidth(),
                    bufferedImage.getHeight(), currentRekognizedOutput);
            isFacialFounded = true;
        } else {
            log.debug("Rendering frame without any rekognized results...");
            isFacialFounded = false;
        }
        return bufferedImage;
    }
}
