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
#include <gst/net/gstnettimeprovider.h>
#include <gst/rtsp-server/rtsp-server.h>

#define DEFAULT_RTSP_PORT "8554"

#ifdef PI

#define DEVICE                                                                 \
  "alsa_input.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.analog-mono"

#define AUDIO_LAUNCHLINE                                                       \
  "pulsesrc "                                                                  \
  "device=" DEVICE " ! audio/x-raw,rate=16000,channels=1 "                      \
  "! avenc_g722 "                                                              \
  "! audio/G722, rate=16000, channels=1 "                                      \
  "! rtpg722pay name=pay0 pt=9"

// audio and video in separate streams
#define AV_LAUNCHLINE                                                          \
  "pulsesrc "                                                                  \
  "device=" DEVICE " ! audio/x-raw,rate=16000,channels=1 "                      \
  "! avenc_g722 "                                                              \
  "! audio/G722, rate=16000, channels=1 "                                      \
  "! rtpg722pay name=pay0 pt=9"                                                \
  " rpicamsrc preview=false video-direction=90r "                              \
  "! video/x-h264,width=800,height=600,framerate=15/1,"                        \
  "profile=constrained-baseline "                                              \
  "! rtph264pay name=pay1 pt=96 "


#else

#define LAUNCHLINE                                                             \
  "v4l2src ! video/x-raw,width=640,height=480 ! x264enc ! rtph264pay "         \
  "name=pay0 pt=96"

#endif

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
    if (rms > 0.1) {
      g_print(
          "{\"rms\":%.3f, \"peak\": %.3f, \"decay\": %.3f, \"normrms\":%.3f}\n",
          rms_dB, peak_dB, decay_dB, rms);
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

static guint initVolumePipeline(GstElement *pipeline) {
  g_print("Initializing volume pipeline");
  GstElement *audiosrc, *audioconvert, *level, *fakesink, *volume;
  GstCaps *caps;
  GstBus *bus;
  guint watch_id;

  caps = gst_caps_from_string("audio/x-raw,channels=1");

  audiosrc = gst_element_factory_make("pulsesrc", NULL);
  audioconvert = gst_element_factory_make("audioconvert", NULL);
  level = gst_element_factory_make("level", NULL);
  volume = gst_element_factory_make("volume", NULL);
  fakesink = gst_element_factory_make("fakesink", NULL);

  if (!pipeline || !audiosrc || !audioconvert || !volume || !level ||
      !fakesink) {
    g_error("failed to create elements");
  }

  gst_bin_add_many(GST_BIN(pipeline), audiosrc, audioconvert, volume, level,
                   fakesink, NULL);
  if (!gst_element_link(audiosrc, audioconvert))
    g_error("Failed to link audiotestsrc and audioconvert");
  if (!gst_element_link(audioconvert, volume))
    g_error("Failed to link audioconvert and level");
  if (!gst_element_link_filtered(volume, level, caps))
    g_error("Failed to link audioconvert and level");
  if (!gst_element_link(level, fakesink))
    g_error("Failed to link level and fakesink");

  g_object_set(G_OBJECT(audiosrc), "device", DEVICE, NULL);
  /* make sure we'll get messages */
  g_object_set(G_OBJECT(level), "post-messages", TRUE, NULL);
  g_object_set(G_OBJECT(level), "interval", (guint64)100000000, NULL);
  g_object_set(G_OBJECT(audiosrc), "volume", (gdouble)10, NULL);
  /* run synced and not as fast as we can */
  g_object_set(G_OBJECT(fakesink), "sync", TRUE, NULL);
  g_object_set(G_OBJECT(volume), "volume", 0.99, NULL);

  bus = gst_element_get_bus(pipeline);
  watch_id = gst_bus_add_watch(bus, message_handler, NULL);

  gst_element_set_state(pipeline, GST_STATE_PLAYING);

  return watch_id;
}

/* this timeout is periodically run to clean up the expired sessions from the
 * pool. This needs to be run explicitly currently but might be done
 * automatically as part of the mainloop. */
static gboolean timeout(GstRTSPServer *server) {
  GstRTSPSessionPool *pool;

  pool = gst_rtsp_server_get_session_pool(server);
  gst_rtsp_session_pool_cleanup(pool);
  g_object_unref(pool);

  return TRUE;
}

int main(int argc, char *argv[]) {
  int optsResult = handleOptions(&argc, &argv);
  if (optsResult != 0) {
    return optsResult;
  }

  gst_init(&argc, &argv);

  GstElement *audioPipeline = gst_pipeline_new(NULL);
  GMainLoop *loop = g_main_loop_new(NULL, FALSE);

  guint watch_id = initVolumePipeline(audioPipeline);

  GstClock *clock = gst_system_clock_obtain();
  gst_net_time_provider_new(clock, "0.0.0.0", 8554);

  GstRTSPServer *server = gst_rtsp_server_new();
  g_object_set(server, "service", port, NULL);
  GstRTSPMountPoints *mounts = gst_rtsp_server_get_mount_points(server);

  g_print("video pipeline: %s\n", AV_LAUNCHLINE);
  GstRTSPMediaFactory *avFactory = gst_rtsp_media_factory_new();
  gst_rtsp_media_factory_set_launch(avFactory, AV_LAUNCHLINE);
  gst_rtsp_media_factory_set_shared(avFactory, TRUE);
  gst_rtsp_media_factory_set_clock(avFactory, clock);

  g_print("audio pipeline: %s\n", AUDIO_LAUNCHLINE);
  GstRTSPMediaFactory *audioFactory = gst_rtsp_media_factory_new();
  gst_rtsp_media_factory_set_launch(audioFactory, AUDIO_LAUNCHLINE);
  gst_rtsp_media_factory_set_shared(audioFactory, TRUE);
  gst_rtsp_media_factory_set_clock(audioFactory, clock);

  gst_rtsp_mount_points_add_factory(mounts, "/audiovideo", avFactory);
  gst_rtsp_mount_points_add_factory(mounts, "/audio", audioFactory);

  g_object_unref(mounts);
  gst_rtsp_server_attach(server, NULL);

  g_print("stream ready at rtsp://127.0.0.1:%s\n", port);

  g_main_loop_run(loop);

  g_print("loop finished\n", port);
  g_print("stopping pipeline\n", port);
  gst_element_set_state(audioPipeline, GST_STATE_NULL);
  g_object_unref(audioPipeline);
  g_source_remove(watch_id);
  g_main_loop_unref(loop);
  return 0;
}
