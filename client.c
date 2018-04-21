/* GStreamer
 * Copyright (C) 2000,2001,2002,2003,2005
 *           Thomas Vander Stichele <thomas at apestaart dot org>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

#include <math.h>
#include <string.h>

#define GLIB_DISABLE_DEPRECATION_WARNINGS

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

int main(int argc, char *argv[]) {

  gst_init(&argc, &argv);


  GstClock *net_clock = gst_net_client_clock_new ("net_clock", "192.168.178.53", 8554, 0);
  if (net_clock == NULL) {
    g_print ("Failed to create net clock client for %s:%d\n",
        "192.168.178.53", 8554);
    return 1;
  }

  /* Wait for the clock to stabilise */
  gst_clock_wait_for_sync (net_clock, GST_CLOCK_TIME_NONE);

  GstElement *rtspSrc, *sink;
  ConsumerPipeline cp;
  GstElement *pipeline = gst_pipeline_new(NULL);
  GMainLoop *loop = g_main_loop_new(NULL, FALSE);


  gst_pipeline_use_clock (GST_PIPELINE (pipeline), net_clock);

  rtspSrc = gst_element_factory_make("rtspsrc", NULL);
  cp.audiosink = gst_element_factory_make("rtpjitterbuffer", NULL);
  cp.videosink = gst_element_factory_make("rtph264depay", NULL);
  GstElement *audiosink = gst_element_factory_make("rtpac3depay", NULL);

  GstElement *vidDecoder = gst_element_factory_make("avdec_h264", NULL);
  GstElement *vidSink = gst_element_factory_make("autovideosink", NULL);

  GstElement *audioDecoder = gst_element_factory_make("a52dec", NULL);
  GstElement *audioSink = gst_element_factory_make("autoaudiosink", NULL);

  g_object_set(G_OBJECT(rtspSrc), "location", "rtsp://192.168.178.53:8554/test",
               NULL);
  g_object_set(G_OBJECT(rtspSrc), "debug", TRUE, NULL);
  g_object_set(G_OBJECT(rtspSrc), "latency", (guint64)1, NULL);

  gst_bin_add_many(GST_BIN(pipeline), rtspSrc, cp.audiosink, audiosink, cp.videosink,
                   vidDecoder, vidSink, audioDecoder, audioSink, NULL);
  if(!gst_element_link(cp.videosink, vidDecoder)){
    g_error("Failed to link rtph264depay and avdec_h264");
  }
  if(!gst_element_link(vidDecoder, vidSink)){
    g_error("Failed to link avdec_h264 and autovideosink");
  }
  if(!gst_element_link(cp.audiosink, audiosink)){
    g_error("Failed to link jitterbuffer and rtpac3depay");
  }
  if(!gst_element_link(audiosink, audioDecoder)){
    g_error("Failed to link rtpac3depay and avdec_ac3");
  }
  if(!gst_element_link(audioDecoder, audioSink)){
    g_error("Failed to link avdec_ac3 and autoaudiosink");
  }

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
