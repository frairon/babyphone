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
#include <gst/rtsp-server/rtsp-server.h>

#define DEFAULT_RTSP_PORT "8554"

static char *port = (char *)DEFAULT_RTSP_PORT;

static GOptionEntry entries[] = {
    {"port", 'p', 0, G_OPTION_ARG_STRING, &port,
     "Port to listen on (default: " DEFAULT_RTSP_PORT ")", "PORT"},
    {NULL}};

static gboolean message_handler(GstBus *bus, GstMessage *message,
                                gpointer data) {

  if (message->type == GST_MESSAGE_ELEMENT) {
    const GstStructure *s = gst_message_get_structure(message);
    const gchar *name = gst_structure_get_name(s);

    if (strcmp(name, "level") != 0) {
      return TRUE;
    }
    gint channels;
    GstClockTime endtime;
    gdouble rms_dB, peak_dB, decay_dB;
    gdouble rms;
    const GValue *array_val;
    const GValue *value;
    GValueArray *rms_arr, *peak_arr, *decay_arr;
    gint i;

    if (!gst_structure_get_clock_time(s, "endtime", &endtime))
      g_warning("Could not parse endtime");

    /* the values are packed into GValueArrays with the value per channel */
    rms_arr =
        (GValueArray *)g_value_get_boxed(gst_structure_get_value(s, "rms"));
    peak_arr =
        (GValueArray *)g_value_get_boxed(gst_structure_get_value(s, "peak"));
    decay_arr =
        (GValueArray *)g_value_get_boxed(gst_structure_get_value(s, "decay"));
    rms_dB = g_value_get_double(g_value_array_get_nth(rms_arr, 0));
    peak_dB = g_value_get_double(g_value_array_get_nth(peak_arr, 0));
    decay_dB = g_value_get_double(g_value_array_get_nth(decay_arr, 0));

    /* converting from dB to normal gives us a value between 0.0 and 1.0 */
    rms = pow(10, rms_dB / 20);
    if (rms > 0.7) {
      g_print("RMS: %f dB, peak: %f dB, decay: %f dB ", rms_dB, peak_dB,
              decay_dB);
      g_print("normalized rms value: %f\n", rms);
    }
  }
  /* we handled the message we want, and ignored the ones we didn't want.
   * so the core can unref the message for us */
  return TRUE;
}

static guint handleOptions(int *argc, char **argv[]) {
  GOptionContext *optctx;
  GError *error = NULL;

  optctx = g_option_context_new("Babyphone Server\n\n");
  g_option_context_add_main_entries(optctx, entries, NULL);
  g_option_context_add_group(optctx, gst_init_get_option_group());
  if (!g_option_context_parse(optctx, argc, argv, &error)) {
    g_printerr("Error parsing options: %s\n", error->message);
    g_option_context_free(optctx);
    g_clear_error(&error);
    return -1;
  }
  g_option_context_free(optctx);
  return 0;
}

static guint initVolumePipeline() {

  GstElement *audiotestsrc, *audioconvert, *level, *fakesink, *volume;
  GstElement *pipeline;
  GstCaps *caps;
  GstBus *bus;
  guint watch_id;

  caps = gst_caps_from_string("audio/x-raw,channels=2");

  pipeline = gst_pipeline_new(NULL);
  audiotestsrc = gst_element_factory_make("pulsesrc", NULL);
  audioconvert = gst_element_factory_make("audioconvert", NULL);
  level = gst_element_factory_make("level", NULL);
  volume = gst_element_factory_make("volume", NULL);
  fakesink = gst_element_factory_make("fakesink", NULL);

  if (!pipeline || !audiotestsrc || !audioconvert || !volume || !level ||
      !fakesink) {
    g_error("failed to create elements");
  }

  gst_bin_add_many(GST_BIN(pipeline), audiotestsrc, audioconvert, volume, level,
                   fakesink, NULL);
  if (!gst_element_link(audiotestsrc, audioconvert))
    g_error("Failed to link audiotestsrc and audioconvert");
  if (!gst_element_link(audioconvert, volume))
    g_error("Failed to link audioconvert and level");
  if (!gst_element_link_filtered(volume, level, caps))
    g_error("Failed to link audioconvert and level");
  if (!gst_element_link(level, fakesink))
    g_error("Failed to link level and fakesink");

  /* make sure we'll get messages */
  g_object_set(G_OBJECT(level), "post-messages", TRUE, NULL);
  g_object_set(G_OBJECT(level), "interval", 100000000, NULL);
  // g_object_set (G_OBJECT (level), "peak-falloff", 10, NULL);
  /* run synced and not as fast as we can */
  g_object_set(G_OBJECT(fakesink), "sync", TRUE, NULL);
  g_object_set(G_OBJECT(volume), "volume", 0.99, NULL);

  bus = gst_element_get_bus(pipeline);
  watch_id = gst_bus_add_watch(bus, message_handler, NULL);

  gst_element_set_state(pipeline, GST_STATE_PLAYING);

  return watch_id;
}

int main(int argc, char *argv[]) {
  int optsResult = handleOptions(&argc, &argv);
  if (optsResult != 0) {
    return optsResult;
  }

  gst_init(&argc, &argv);

  guint watch_id = initVolumePipeline();

  GstRTSPServer *server = gst_rtsp_server_new();
  g_object_set(server, "service", port, NULL);
  GstRTSPMountPoints *mounts = gst_rtsp_server_get_mount_points(server);
  GstRTSPMediaFactory *factory = gst_rtsp_media_factory_new();
  gst_rtsp_media_factory_set_shared(factory, TRUE);
  gst_rtsp_media_factory_set_launch(
      factory, "v4l2src ! video/x-raw,width=640,height=480 ! x264enc ! "
               "rtph264pay name=pay0 pt=96");

  gst_rtsp_mount_points_add_factory(mounts, "/test", factory);

  /* we need to run a GLib main loop to get the messages */
  g_object_unref(mounts);
  gst_rtsp_server_attach(server, NULL);

  g_print("stream ready at rtsp://127.0.0.1:%s/test\n", port);
  GMainLoop *loop = g_main_loop_new(NULL, FALSE);
  g_main_loop_run(loop);

  g_source_remove(watch_id);
  g_main_loop_unref(loop);
  return 0;
}
