# Builds libemucore.so: Mednafen 1.32.1's ss module (Sega Saturn) plus the common Mednafen code
# it needs, with the frontend hooks stubbed out in driver_stubs.cpp. The Mednafen checkout is not
# modified in any way; everything Mednafen-specific lives here and in config/config.h.

cmake_minimum_required(VERSION 3.22)

project(emucore LANGUAGES C CXX)

# Absolute path of the Mednafen checkout, passed from emucore/build.gradle.kts. Defaults to the
# sibling external/sapphirine-core so CMake also works when run from an IDE directly.
set(CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../external/sapphirine-core" CACHE PATH
	"Path of the Mednafen 1.32.1 (or compatible) checkout")

if(NOT EXISTS "${CORE_DIR}/src/ss/ss.cpp")
	message(FATAL_ERROR
		"CORE_DIR (${CORE_DIR}) does not look like the Mednafen core checkout: ${CORE_DIR}/src/ss is missing.")
endif()

# ---------------------------------------------------------------------------
# Mednafen's code requirements (README.PORTING, configure.ac):
#   - gnu++11 for C++, gnu99 for C (configure.ac hardcodes -std=gnu++11/-std=gnu99)
#   - -fwrapv, -fsigned-char, -fno-strict-aliasing
#   - no LTO, no -ffast-math, no -mcpu (leave codegen to the build type and the generic -O2)
# The ss module gets no -mtune=haswell: that is x86-only.
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS ON)      # gnu++11
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_EXTENSIONS ON)        # gnu99

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fwrapv -fsigned-char -fno-strict-aliasing")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fwrapv -fsigned-char -fno-strict-aliasing")

# include/mednafen is a symlink to src in the Mednafen checkout, so -I<core>/include resolves
# the <mednafen/...> includes; config/ holds our hand-written config.h (a stand-in for the
# autotools-generated one).
#
# The third-party headers <trio/...>, <minilzo/...> and <zstd/...> are matched through a small
# staging directory of symlinks, mirroring what Mednafen's configure does with AC_CONFIG_LINKS
# (for the in-tree build: include/trio -> src/trio, include/minilzo -> include/minilzo_internal,
# include/zstd -> src/zstd). src/ itself is NOT on the include path, like in the autotools
# build: it contains a time.h that would shadow libc's when a system header (e.g. pthread.h)
# pulls <time.h> in.
set(mdfn_dirs ${CMAKE_CURRENT_LIST_DIR}/config ${CORE_DIR}/include)
set(mdfn_defines HAVE_CONFIG_H)
set(mdfn_stage "${CMAKE_CURRENT_BINARY_DIR}/mednafen-include")
file(MAKE_DIRECTORY "${mdfn_stage}")
file(CREATE_LINK "${CORE_DIR}/src/trio" "${mdfn_stage}/trio" SYMBOLIC)
file(CREATE_LINK "${CORE_DIR}/include/minilzo_internal" "${mdfn_stage}/minilzo" SYMBOLIC)
file(CREATE_LINK "${CORE_DIR}/src/zstd" "${mdfn_stage}/zstd" SYMBOLIC)
list(APPEND mdfn_dirs "${mdfn_stage}")

# ---------------------------------------------------------------------------
# Mednafen common code the ss module pulls in. Mirrors src/Makefile.am's mednafen_SOURCES
# and the per-directory Makefile.am.inc files, minus what nothing here links against:
# no other systems, no drivers/, drivers_dos/, drivers_libxxx/, sexyal/, net/, no netplay.cpp
# (it would drag net/ in; its entry points are stubbed in driver_stubs.cpp), no
# sound/Blip_Buffer.cpp+Stereo_Buffer.cpp (NEED_BLIP_BUFFER is off for ss), and no
# PSFLoader.cpp, SSFLoader.cpp, SNSFLoader.cpp or SPCReader.cpp: they are the PSF/SNSF/SSF
# sound-format loaders, nothing in the ss build references them, and PSFLoader.cpp uses
# iconv() unconditionally, which is not in libc (bionic).
# cdrom/scsicd.cpp is likewise left out: it is the PC-FX/PCE SCSI CD glue (SCSICD_*), referenced
# only from src/pce and src/pcfx, which are not built.
set(MDN_ROOT_SOURCES
	debug.cpp
	error.cpp
	mempatcher.cpp
	settings.cpp
	endian.cpp
	mednafen.cpp
	git.cpp
	file.cpp
	general.cpp
	memory.cpp
	state.cpp
	state_rewind.cpp
	movie.cpp
	player.cpp
	tests.cpp          # Compiler sanity checks (README.PORTING: keep, at least in debug builds)
	testsexp.cpp
	qtrecord.cpp
	IPSPatcher.cpp
	VirtualFS.cpp
	NativeVFS.cpp
	Stream.cpp
	MemoryStream.cpp
	ExtMemStream.cpp
	FileStream.cpp
	MTStreamReader.cpp
	time/Time_POSIX.cpp
	mthreading/MThreading_POSIX.cpp   # ss's VDP2 renderer runs a thread (ss.affinity.vdp2)
)

set(MDN_CDROM_SOURCES
	cdrom/crc32.cpp
	cdrom/galois.cpp
	cdrom/l-ec.cpp
	cdrom/recover-raw.cpp
	cdrom/lec.cpp
	cdrom/CDUtility.cpp
	cdrom/CDInterface.cpp
	cdrom/CDInterface_MT.cpp
	cdrom/CDInterface_ST.cpp
	cdrom/CDAccess.cpp
	cdrom/CDAccess_Image.cpp
	cdrom/CDAccess_CCD.cpp
	cdrom/CDAFReader.cpp
	cdrom/CDAFReader_Vorbis.cpp
	cdrom/CDAFReader_MPC.cpp
	cdrom/CDAFReader_PCM.cpp
)

set(MDN_SOUND_SOURCES
	sound/Fir_Resampler.cpp
	sound/WAVRecord.cpp
	sound/okiadpcm.cpp
	sound/DSPUtility.cpp
	sound/SwiftResampler.cpp
	sound/OwlResampler.cpp
	sound/CassowaryResampler.cpp
)

set(MDN_MISC_SOURCES
	string/escape.cpp
	string/string.cpp
	hash/md5.cpp
	hash/sha1.cpp
	hash/sha256.cpp
	hash/crc.cpp
	compress/ArchiveReader.cpp
	compress/ZIPReader.cpp
	compress/GZFileStream.cpp
	compress/DecompressFilter.cpp
	compress/ZstdDecompressFilter.cpp
	compress/ZLInflateFilter.cpp
	resampler/resample.c
	cputest/cputest.c   # cputest_get_flags() is called from SwiftResampler (x86/ppc branches need ARCH_* defines we don't set)
	hw_cpu/m68k/m68k.cpp
	cdplay/cdplay.cpp
	demo/demo.cpp
)

set(MDN_VIDEO_SOURCES
	video/surface.cpp
	video/convert.cpp
	video/tblur.cpp
	video/Deinterlacer.cpp
	video/Deinterlacer_Simple.cpp
	video/Deinterlacer_Blend.cpp
	video/resize.cpp
	video/video.cpp
	video/primitives.cpp
	video/png.cpp
	video/text.cpp
	video/font-data.cpp
	video/font-data-18x18.c
	video/font-data-12x13.c
)

# The ss module itself (src/ss/Makefile.am's libss_a_SOURCES; ssf.cpp is the separate
# libssfplay for SSF playback and stays out).
set(MDN_SS_SOURCES
	ss/db.cpp
	ss/stvio.cpp
	ss/ak93c45.cpp
	ss/cdb.cpp
	ss/sound.cpp
	ss/cart.cpp
	ss/cart/backup.cpp
	ss/cart/cs1ram.cpp
	ss/cart/bootrom.cpp
	ss/cart/extram.cpp
	ss/cart/rom.cpp
	ss/cart/ar4mp.cpp
	ss/cart/stv.cpp
	ss/ss.cpp
	ss/scu_dsp_gen.cpp
	ss/scu_dsp_mvi.cpp
	ss/scu_dsp_jmp.cpp
	ss/scu_dsp_misc.cpp
	ss/vdp1.cpp
	ss/vdp1_line.cpp
	ss/vdp1_sprite.cpp
	ss/vdp1_poly.cpp
	ss/vdp2.cpp
	ss/vdp2_render.cpp
	ss/smpc.cpp
	ss/input/multitap.cpp
	ss/input/gamepad.cpp
	ss/input/3dpad.cpp
	ss/input/mouse.cpp
	ss/input/wheel.cpp
	ss/input/mission.cpp
	ss/input/gun.cpp
	ss/input/keyboard.cpp
	ss/input/jpkeyboard.cpp
)

# ---------------------------------------------------------------------------
# Bundled third-party C libraries, kept without HAVE_CONFIG_H (Mednafen builds them the same
# way). tremor needs -I its own directory; mpcdec needs MPC_FIXED_POINT; zstd gets the defines
# from the Makefile.am.inc.
set(VENDOR_SOURCES
	trio/trio.c
	trio/trionan.c
	trio/triostr.c
	minilzo/minilzo.c
	quicklz/quicklz.c
	zstd/common/entropy_common.c
	zstd/common/error_private.c
	zstd/common/fse_decompress.c
	zstd/common/xxhash.c
	zstd/common/zstd_common.c
	zstd/decompress/huf_decompress.c
	zstd/decompress/zstd_ddict.c
	zstd/decompress/zstd_decompress_block.c
	zstd/decompress/zstd_decompress.c
)

set(TREMOR_SOURCES
	tremor/mdct.c
	tremor/block.c
	tremor/window.c
	tremor/synthesis.c
	tremor/info.c
	tremor/floor1.c
	tremor/floor0.c
	tremor/vorbisfile.c
	tremor/res012.c
	tremor/mapping0.c
	tremor/registry.c
	tremor/codebook.c
	tremor/sharedbook.c
	tremor/framing.c
	tremor/bitwise.c
)

set(MPCDEC_SOURCES
	mpcdec/huffman.c
	mpcdec/mpc_decoder.c
	mpcdec/mpc_reader.c
	mpcdec/requant.c
	mpcdec/streaminfo.c
	mpcdec/synth_filter.c
	mpcdec/mpc_bits_reader.c
	mpcdec/mpc_demux.c
	mpcdec/crc32.c
)

set(mednafen_sources
	${MDN_ROOT_SOURCES}
	${MDN_CDROM_SOURCES}
	${MDN_SOUND_SOURCES}
	${MDN_MISC_SOURCES}
	${MDN_VIDEO_SOURCES}
	${MDN_SS_SOURCES}
)
list(TRANSFORM mednafen_sources PREPEND "${CORE_DIR}/src/")

# Our files (the frontend host, the driver stubs and the JNI boundary), then the Mednafen
# sources, all under <core>/src.
add_library(emucore SHARED
	android_host.cpp
	audio_stream.cpp
	driver_stubs.cpp
	jni.cpp
	${mednafen_sources}
)

target_include_directories(emucore PRIVATE ${mdfn_dirs})
target_compile_definitions(emucore PRIVATE ${mdfn_defines})

# Vendor sources: also compiled into emucore, but without HAVE_CONFIG_H (they get just their
# own defines; the target's -fsanitize-like flags do not concern them) and with warnings off.
set(vendor_paths)
foreach(src ${VENDOR_SOURCES})
	list(APPEND vendor_paths "${CORE_DIR}/src/${src}")
endforeach()
target_sources(emucore PRIVATE ${vendor_paths})
set_source_files_properties(${vendor_paths} PROPERTIES
	COMPILE_OPTIONS "-w"
	COMPILE_DEFINITIONS "ZSTD_NO_TRACE=1;ZSTD_HAVE_WEAK_SYMBOLS=0"
)

set(tremor_paths)
foreach(src ${TREMOR_SOURCES})
	list(APPEND tremor_paths "${CORE_DIR}/src/${src}")
endforeach()
target_sources(emucore PRIVATE ${tremor_paths})
set_source_files_properties(${tremor_paths} PROPERTIES
	COMPILE_OPTIONS "-w;-I${CORE_DIR}/src/tremor"
)

set(mpcdec_paths)
foreach(src ${MPCDEC_SOURCES})
	list(APPEND mpcdec_paths "${CORE_DIR}/src/${src}")
endforeach()
target_sources(emucore PRIVATE ${mpcdec_paths})
set_source_files_properties(${mpcdec_paths} PROPERTIES
	COMPILE_OPTIONS "-w"
	COMPILE_DEFINITIONS "MPC_FIXED_POINT=1"
)

# Catch every missing symbol at link time instead of leaving it to a crash at runtime.
target_link_options(emucore PRIVATE -Wl,--no-undefined)

# Android's bundled zlib (png.cpp, GZFileStream.cpp, testsexp.cpp, ...), libm, libandroid
# (ANativeWindow, in android_host.cpp / jni.cpp) and libaaudio (audio_stream.cpp); log for the
# core's own output. Threads are part of bionic.
target_link_libraries(emucore PRIVATE log z m android aaudio)

# Our own files are C++17 (Mednafen itself stays at the gnu++11 configure.ac asks for); the
# later -std on the command line wins over CMAKE_CXX_STANDARD's.
set_source_files_properties(android_host.cpp audio_stream.cpp driver_stubs.cpp jni.cpp
	PROPERTIES COMPILE_OPTIONS "-std=gnu++17")
