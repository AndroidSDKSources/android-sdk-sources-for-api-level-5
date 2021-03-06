/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics;

public class PixelFormat
{
    /* these constants need to match those
       in ui/PixelFormat.h & pixelflinger/format.h */
    
    public static final int UNKNOWN     = 0;

    /** System chooses a format that supports translucency (many alpha bits) */
    public static final int TRANSLUCENT = -3;

    /** 
     * System chooses a format that supports transparency
     * (at least 1 alpha bit) 
     */    
    public static final int TRANSPARENT = -2;

    /** System chooses an opaque format (no alpha bits required) */
    public static final int OPAQUE      = -1;

    public static final int RGBA_8888   = 1;
    public static final int RGBX_8888   = 2;
    public static final int RGB_888     = 3;
    public static final int RGB_565     = 4;

    public static final int RGBA_5551   = 6;
    public static final int RGBA_4444   = 7;
    public static final int A_8         = 8;
    public static final int L_8         = 9;
    public static final int LA_88       = 0xA;
    public static final int RGB_332     = 0xB;
    
    /**
     * YCbCr formats, used for video. These are not necessarily supported
     * by the hardware.
     */
    public static final int YCbCr_422_SP= 0x10;

    /** YCbCr format used for images, which uses the NV21 encoding format.   
     *  This is the default format for camera preview images, when not
     *  otherwise set with 
     *  {@link android.hardware.Camera.Parameters#setPreviewFormat(int)}.
     */
    public static final int YCbCr_420_SP= 0x11;

    /** YCbCr format used for images, which uses YUYV (YUY2) encoding format.
     *  This is an alternative format for camera preview images. Whether this
     *  format is supported by the camera hardware can be determined by
     *  {@link android.hardware.Camera.Parameters#getSupportedPreviewFormats()}.
     */
    public static final int YCbCr_422_I = 0x14;

    /**
     * Encoded formats.  These are not necessarily supported by the hardware.
     */
    public static final int JPEG        = 0x100;

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    native private static void nativeClassInit();
    static { nativeClassInit(); }

    public static native void getPixelFormatInfo(int format, PixelFormat info);
    public static boolean formatHasAlpha(int format) {
        switch (format) {
            case PixelFormat.A_8:
            case PixelFormat.LA_88:
            case PixelFormat.RGBA_4444:
            case PixelFormat.RGBA_5551:
            case PixelFormat.RGBA_8888:
            case PixelFormat.TRANSLUCENT:
            case PixelFormat.TRANSPARENT:
                return true;
        }
        return false;
    }
    
    public int  bytesPerPixel;
    public int  bitsPerPixel;
}
