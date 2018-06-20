#include <math.h>
#include <string.h>

#include <gst/gst.h>
#include <gst/net/gstnet.h>
#include <gst/net/gstnettimeprovider.h>

typedef struct {
  GstElement *pipeline;
  GstElement *source;
  GstElement *audiosink;
  GstElement *videosink;
} ConsumerPipeline;

static void pad_added_handler(GstElement *src, GstPad *new_pad,
                              ConsumerPipeline *data) {
  GstPadLinkReturn ret;
  GstCaps *new_pad_caps = NULL;
  GstStructure *new_pad_struct = NULL;
  const gchar *new_pad_type = NULL;

  g_print("Received new pad '%s' from '%s':\n", GST_PAD_NAME(new_pad),
          GST_ELEMENT_NAME(src));

  /* Check the new pad's type */
  new_pad_caps = gst_pad_get_current_caps(new_pad);
  gchar *type = gst_caps_to_string(new_pad_caps);
  if (g_str_has_prefix(type, "application/x-rtp, media=(string)video")) {
    g_print("Video found!!\n");
    GstPad *sink_pad = gst_element_get_static_pad(data->videosink, "sink");
    if (!gst_pad_is_linked(sink_pad)) {
      ret = gst_pad_link(new_pad, sink_pad);
      if (GST_PAD_LINK_FAILED(ret)) {
        g_print("Linking failed.\n");
      }
      gst_object_unref(sink_pad);
    }
  } else if (g_str_has_prefix(type, "application/x-rtp, media=(string)audio")) {
    g_print("Audio found!!\n");
    GstPad *sink_pad = gst_element_get_static_pad(data->audiosink, "sink");
    if (!gst_pad_is_linked(sink_pad)) {
      ret = gst_pad_link(new_pad, sink_pad);
      if (GST_PAD_LINK_FAILED(ret)) {
        g_print("Linking failed.\n");
      }
      gst_object_unref(sink_pad);
    }
  } else {
    g_print("something else found....");
  }

  g_free(type);

  /* Unreference the new pad's caps, if we got them */
  if (new_pad_caps != NULL)
    gst_caps_unref(new_pad_caps);
}

// #define USE_VIDEO

int main(int argc, char *argv[]) {

  gst_init(&argc, &argv);

  GstClock *net_clock =
      gst_net_client_clock_new("net_clock", "192.168.178.52", 8554, 0);
  if (net_clock == NULL) {
    g_print("Failed to create net clock client for %s:%d\n", "192.168.178.52",
            8554);
    return 1;
  }

  /* Wait for the clock to stabilise */
  gst_clock_wait_for_sync(net_clock, GST_CLOCK_TIME_NONE);

  GstElement *rtspSrc, *sink;
  ConsumerPipeline cp;
  GstElement *pipeline = gst_pipeline_new(NULL);
  GMainLoop *loop = g_main_loop_new(NULL, FALSE);

  gst_pipeline_use_clock(GST_PIPELINE(pipeline), net_clock);

  // gst_pipeline_set_latency(GST_PIPELINE(pipeline), 500 * GST_MSECOND);

  rtspSrc = gst_element_factory_make("rtspsrc", NULL);
  GstElement *audioJitterBuffer =
      gst_element_factory_make("rtpjitterbuffer", "ajitterbuffer");
  g_object_set(G_OBJECT(audioJitterBuffer), "drop-on-latency", (gboolean)1,
               NULL);
  // g_object_set(G_OBJECT(audioJitterBuffer), "latency", (guint)500, NULL);
  GstElement *videoJitterBuffer =
      gst_element_factory_make("rtpjitterbuffer", "vjitterbuffer");
  g_object_set(G_OBJECT(videoJitterBuffer), "drop-on-latency", (gboolean)1,
               NULL);
  // g_object_set(G_OBJECT(videoJitterBuffer), "latency", (guint)500, NULL);
  GstElement *audiodepay = gst_element_factory_make("rtpg722depay", NULL);
  GstElement *videodepay = gst_element_factory_make("rtph264depay", NULL);
  GstElement *audioQueue = gst_element_factory_make("queue", NULL);
  GstElement *videoQueue = gst_element_factory_make("queue", NULL);

  GstElement *vidDecoder = gst_element_factory_make("avdec_h264", NULL);
  GstElement *vidSink = gst_element_factory_make("xvimagesink", NULL);
  g_object_set(G_OBJECT(vidSink), "sync", (gboolean)0, NULL);

  GstElement *audioDecoder = gst_element_factory_make("avdec_g722", NULL);
  GstElement *audioSink = gst_element_factory_make("autoaudiosink", NULL);
  g_object_set(G_OBJECT(audioSink), "sync", (gboolean)1, NULL);

  g_object_set(G_OBJECT(rtspSrc), "location", "rtsp://192.168.178.52:8554/audio",
               NULL);
  g_object_set(G_OBJECT(rtspSrc), "debug", TRUE, NULL);
  // g_object_set(G_OBJECT(rtspSrc), "latency", (guint64)101*GST_MSECOND, NULL);

  gst_bin_add_many(GST_BIN(pipeline), rtspSrc, audioJitterBuffer, audiodepay,
                   audioQueue, audioDecoder, audioSink, NULL);
#ifdef USE_VIDEO
  gst_bin_add_many(GST_BIN(pipeline), videoJitterBuffer, videodepay, videoQueue,
                   vidDecoder, vidSink, NULL);
  if (!gst_element_link_many(videoJitterBuffer, videodepay, videoQueue,
                             vidDecoder, vidSink, NULL)) {
    g_error("Failed to link video stream");
  }
#endif
  if (!gst_element_link_many(audioJitterBuffer, audiodepay, audioDecoder, audioQueue,
                              audioSink, NULL)) {
    g_error("Failed to link audio pipeline");
  }

  cp.audiosink = audioJitterBuffer;
  cp.videosink = videoJitterBuffer;

  /* listen for newly created pads */
  g_signal_connect(rtspSrc, "pad-added", G_CALLBACK(pad_added_handler), &cp);

  gst_element_set_state(pipeline, GST_STATE_PLAYING);

  g_print("Starting pipeline...\n");
  g_main_loop_run(loop);
  g_print("stop...\n");

  gst_element_set_state(pipeline, GST_STATE_NULL);
  g_object_unref(pipeline);
  g_main_loop_unref(loop);
  return 0;
}
