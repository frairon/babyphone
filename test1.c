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

#include <string.h>
#include <math.h>

#define GLIB_DISABLE_DEPRECATION_WARNINGS

#include <gst/gst.h>

static gboolean
message_handler (GstBus * bus, GstMessage * message, gpointer data)
{

  if (message->type == GST_MESSAGE_ELEMENT) {
    const GstStructure *s = gst_message_get_structure (message);
    const gchar *name = gst_structure_get_name (s);

    if (strcmp (name, "level") == 0) {
      gint channels;
      GstClockTime endtime;
      gdouble rms_dB, peak_dB, decay_dB;
      gdouble rms;
      const GValue *array_val;
      const GValue *value;
      GValueArray *rms_arr, *peak_arr, *decay_arr;
      gint i;

      if (!gst_structure_get_clock_time (s, "endtime", &endtime))
        g_warning ("Could not parse endtime");

      /* the values are packed into GValueArrays with the value per channel */
      array_val = gst_structure_get_value (s, "rms");
      rms_arr = (GValueArray *) g_value_get_boxed (array_val);
      array_val = gst_structure_get_value (s, "peak");
      peak_arr = (GValueArray *) g_value_get_boxed (array_val);
      array_val = gst_structure_get_value (s, "decay");
      decay_arr = (GValueArray *) g_value_get_boxed (array_val);
      value = g_value_array_get_nth (rms_arr, 0);
      rms_dB = g_value_get_double (value);
      value = g_value_array_get_nth (peak_arr, 0);
      peak_dB = g_value_get_double (value);
      value = g_value_array_get_nth (decay_arr, 0);
      decay_dB = g_value_get_double (value);

      /* converting from dB to normal gives us a value between 0.0 and 1.0 */
      rms = pow (10, rms_dB / 20);
      if(rms > 0.7){
        g_print ("RMS: %f dB, peak: %f dB, decay: %f dB ",
            rms_dB, peak_dB, decay_dB);
        g_print ("normalized rms value: %f\n", rms);
      }
    }
  }
  /* we handled the message we want, and ignored the ones we didn't want.
   * so the core can unref the message for us */
  return TRUE;
}

int
main (int argc, char *argv[])
{
  GstElement *audiotestsrc, *audioconvert, *level, *fakesink, *volume;
  GstElement *pipeline;
  GstCaps *caps;
  GstBus *bus;
  guint watch_id;
  GMainLoop *loop;

  gst_init (&argc, &argv);

  caps = gst_caps_from_string ("audio/x-raw,channels=2");

  pipeline = gst_pipeline_new (NULL);
  audiotestsrc = gst_element_factory_make ("pulsesrc", NULL);
  audioconvert = gst_element_factory_make ("audioconvert", NULL);
  level = gst_element_factory_make ("level", NULL);
  volume = gst_element_factory_make ("volume", NULL);
  fakesink = gst_element_factory_make ("fakesink", NULL);

   if(!pipeline ||   !audiotestsrc ||   !audioconvert ||!volume ||  !level ||   !fakesink){
     g_error("failed to create elements");
   }

  gst_bin_add_many (GST_BIN (pipeline), audiotestsrc, audioconvert ,volume, level,
      fakesink, NULL);
  if (!gst_element_link (audiotestsrc, audioconvert))
    g_error ("Failed to link audiotestsrc and audioconvert");
  if (!gst_element_link(audioconvert, volume))
    g_error ("Failed to link audioconvert and level");
  if (!gst_element_link_filtered (volume,level, caps))
    g_error ("Failed to link audioconvert and level");
  if (!gst_element_link (level, fakesink))
    g_error ("Failed to link level and fakesink");

  /* make sure we'll get messages */
  g_object_set (G_OBJECT (level), "post-messages", TRUE, NULL);
  g_object_set (G_OBJECT (level), "interval", 3000000000, NULL);
  // g_object_set (G_OBJECT (level), "peak-falloff", 10, NULL);
  /* run synced and not as fast as we can */
  g_object_set (G_OBJECT (fakesink), "sync", TRUE, NULL);
  g_object_set (G_OBJECT (volume), "volume", 0.99, NULL);

  bus = gst_element_get_bus (pipeline);
  watch_id = gst_bus_add_watch (bus, message_handler, NULL);

  gst_element_set_state (pipeline, GST_STATE_PLAYING);

  /* we need to run a GLib main loop to get the messages */
  loop = g_main_loop_new (NULL, FALSE);
  g_main_loop_run (loop);

  g_source_remove (watch_id);
  g_main_loop_unref (loop);
  return 0;
}
