from cffi import FFI
ffibuilder = FFI()

# cdef() expects a single string declaring the C types, functions and
# globals needed to use the shared object. It must be in valid C syntax.
ffibuilder.cdef("""

typedef struct {
  void *ptr;
  char *name;
}
psymodellist_t;


typedef void *faacEncHandle;

typedef struct faacEncConfiguration
{
    /* config version */
    int version;

    /* library version */
    char *name;

    /* copyright string */
    char *copyright;

    /* MPEG version, 2 or 4 */
    unsigned int mpegVersion;

    /* AAC object type */
    unsigned int aacObjectType;

    union {
        /* Joint coding mode */
        unsigned int jointmode;
        /* compatibility alias */
        unsigned int allowMidside;
    };

    /* Use one of the channels as LFE channel */
    unsigned int useLfe;

    /* Use Temporal Noise Shaping */
    unsigned int useTns;

    /* bitrate / channel of AAC file */
    unsigned long bitRate;

    /* AAC file frequency bandwidth */
    unsigned int bandWidth;

    /* Quantizer quality */
    unsigned long quantqual;

    /* Bitstream output format (0 = Raw; 1 = ADTS) */
    unsigned int outputFormat;

    /* psychoacoustic model list */
    psymodellist_t *psymodellist;

    /* selected index in psymodellist */
    unsigned int psymodelidx;

    /*
		PCM Sample Input Format
		0	FAAC_INPUT_NULL			invalid, signifies a misconfigured config
		1	FAAC_INPUT_16BIT		native endian 16bit
		2	FAAC_INPUT_24BIT		native endian 24bit in 24 bits		(not implemented)
		3	FAAC_INPUT_32BIT		native endian 24bit in 32 bits		(DEFAULT)
		4	FAAC_INPUT_FLOAT		32bit floating point
    */
    unsigned int inputFormat;

    /* block type enforcing (SHORTCTL_NORMAL/SHORTCTL_NOSHORT/SHORTCTL_NOLONG) */
    int shortctl;

	/*
		Channel Remapping

		Default			0, 1, 2, 3 ... 63  (64 is MAX_CHANNELS in coder.h)

		WAVE 4.0		2, 0, 1, 3
		WAVE 5.0		2, 0, 1, 3, 4
		WAVE 5.1		2, 0, 1, 4, 5, 3
		AIFF 5.1		2, 0, 3, 1, 4, 5
	*/
    int channel_map[64];
    int pnslevel;
} faacEncConfiguration, *faacEncConfigurationPtr;


    faacEncHandle faacEncOpen(unsigned long sampleRate,
				  unsigned int numChannels,
				  unsigned long *inputSamples,
                                  unsigned long *maxOutputBytes
                                 );



faacEncConfigurationPtr
  faacEncGetCurrentConfiguration(faacEncHandle hEncoder);

int faacEncSetConfiguration(faacEncHandle hEncoder,
				    faacEncConfigurationPtr config);


int faacEncEncode(faacEncHandle hEncoder, int32_t * inputBuffer, unsigned int samplesInput,
			 unsigned char *outputBuffer,
			 unsigned int bufferSize);


int faacEncClose(faacEncHandle hEncoder);

""")

# set_source() gives the name of the python extension module to
# produce, and some C source code as a string.  This C code needs
# to make the declarated functions, types and globals available,
# so it is often just the "#include".
ffibuilder.set_source("_faac_cffi",
"""
     #include "faac.h"   // the C header of the library
""",
     libraries=['faac'])   # library name, for the linker

if __name__ == "__main__":
    ffibuilder.compile(verbose=True)
