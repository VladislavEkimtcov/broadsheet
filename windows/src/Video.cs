using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Windows.Graphics.DirectX.Direct3D11;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Media.Streaming.Adaptive;

namespace Broadsheet
{
    /// <summary>
    /// Plays a video with Windows' own player (Windows.Media.Playback.MediaPlayer, WinRT, in the
    /// box since Windows 10 1809) and hands its frames to WPF as a WriteableBitmap. HLS (.m3u8)
    /// goes through AdaptiveMediaSource, MP4 straight in.
    ///
    /// Why not WPF's MediaElement: it sits on the old Windows Media Player runtime, which can't
    /// open HLS and crashes natively on some images (0xC000041D on DOORWEDGE's IoT LTSC 2021,
    /// even on a plain MP4). WinRT's player has no WPF control, so it runs in frame-server mode:
    /// on each frame it scales the picture into a D3D11 texture of our size, we copy that to a
    /// staging texture, map it and copy the rows out. About 2 ms a frame at 480p and 11 ms at
    /// 1080p on a Haswell i5 (on the player's worker thread), so the texture is never bigger
    /// than the window shows. A frame that comes while the last one hasn't been drawn yet is
    /// dropped. D3D11 is reached through its vtables, since there's nothing to reference.
    ///
    /// Must run in a desktop session: in session 0 (a service, an SSH shell) MediaPlayer can't
    /// decode (0x887A0022) and its frame server crashes the process.
    ///
    /// Events come on the dispatcher's thread. One Video plays one stream; Dispose it after.
    /// </summary>
    sealed class Video : IDisposable
    {
        public event Action Opened, Ended, FrameShown;
        public event Action<string> Failed;

        readonly Dispatcher ui;
        readonly object gate = new object();
        MediaPlayer player;
        AdaptiveMediaSource adaptive;
        IntPtr device, context, texture, staging;
        IDirect3DSurface surface;
        int texW, texH;
        byte[] pixels;
        bool pending, disposed, failed;
        volatile int maxW = 1920, maxH = 1080;
        WriteableBitmap bitmap;

        public Video(Dispatcher ui)
        {
            this.ui = ui;
        }

        /// <summary>The picture; replaced when the video's size changes (FrameShown says when).</summary>
        public WriteableBitmap Bitmap { get { return bitmap; } }

        /// <summary>The largest frame worth making, in device pixels: the player's size on screen.</summary>
        public void SetTarget(int w, int h)
        {
            maxW = Math.Max(16, w);
            maxH = Math.Max(16, h);
        }

        public void Open(string url)
        {
            try
            {
                D3D.CreateDevice(out device, out context);
                player = new MediaPlayer();
                player.AutoPlay = false;
                player.IsVideoFrameServerEnabled = true;
                player.MediaOpened += (s, e) => Post(() => { if (Opened != null) Opened(); });
                player.MediaEnded += (s, e) => Post(() => { if (Ended != null) Ended(); });
                player.MediaFailed += (s, e) => Fail(e.Error + (string.IsNullOrEmpty(e.ErrorMessage) ? "" : ": " + e.ErrorMessage)
                    + " (0x" + e.ExtendedErrorCode.HResult.ToString("X8") + ")");
                player.VideoFrameAvailable += (s, e) => OnFrame();

                if (url.IndexOf(".m3u8", StringComparison.OrdinalIgnoreCase) >= 0)
                {
                    var op = AdaptiveMediaSource.CreateFromUriAsync(new Uri(url));
                    op.Completed = (o, status) =>
                    {
                        AdaptiveMediaSourceCreationResult r = null;
                        string error = null;
                        try { r = o.GetResults(); }
                        catch (Exception ex) { error = ex.Message; }
                        Post(() => Start(r, error));
                    };
                }
                else
                {
                    player.Source = MediaSource.CreateFromUri(new Uri(url));
                    player.Play();
                }
            }
            catch (Exception ex)
            {
                Fail(ex.Message);
            }
        }

        void Start(AdaptiveMediaSourceCreationResult r, string error)
        {
            if (disposed) return;
            if (r == null || r.Status != AdaptiveMediaSourceCreationStatus.Success || r.MediaSource == null)
            {
                Fail(error ?? "the stream's playlist: " + (r != null ? r.Status.ToString() : "no answer"));
                return;
            }
            adaptive = r.MediaSource;
            // Start on a picture worth looking at (about 720p) rather than the bottom rung; the
            // player climbs or falls from there with the network.
            uint best = 0;
            foreach (uint b in adaptive.AvailableBitrates)
                if (b <= 2500000 && b > best) best = b;
            if (best > 0) adaptive.InitialBitrate = best;
            player.Source = MediaSource.CreateFromAdaptiveMediaSource(adaptive);
            player.Play();
        }

        public bool Buffering
        {
            get
            {
                try { return player != null && player.PlaybackSession.PlaybackState == MediaPlaybackState.Buffering; }
                catch (Exception) { return false; }
            }
        }

        public TimeSpan Position
        {
            get { try { return player != null ? player.PlaybackSession.Position : TimeSpan.Zero; } catch (Exception) { return TimeSpan.Zero; } }
            set { try { if (player != null) player.PlaybackSession.Position = value; } catch (Exception) { } }
        }

        /// <summary>Zero when it isn't known (yet), as for a live stream.</summary>
        public TimeSpan Duration
        {
            get { try { return player != null ? player.PlaybackSession.NaturalDuration : TimeSpan.Zero; } catch (Exception) { return TimeSpan.Zero; } }
        }

        public void Play() { if (player != null) player.Play(); }
        public void Pause() { if (player != null) player.Pause(); }

        void Fail(string reason)
        {
            Post(() =>
            {
                if (failed || disposed) return;
                failed = true;
                if (Failed != null) Failed(reason);
            });
        }

        void Post(Action a)
        {
            ui.BeginInvoke(DispatcherPriority.Normal, new Action(() => { if (!disposed) a(); }));
        }

        // The player's worker thread.
        void OnFrame()
        {
            lock (gate)
            {
                if (disposed || pending || player == null) return;
                try
                {
                    MediaPlaybackSession session = player.PlaybackSession;
                    int vw = (int)session.NaturalVideoWidth, vh = (int)session.NaturalVideoHeight;
                    if (vw <= 0 || vh <= 0) return;
                    double scale = Math.Min(1.0, Math.Min(maxW / (double)vw, maxH / (double)vh));
                    int w = Math.Max(2, (int)Math.Round(vw * scale)), h = Math.Max(2, (int)Math.Round(vh * scale));
                    if (w != texW || h != texH)
                    {
                        FreeTextures();
                        texture = D3D.CreateTexture(device, w, h, false);
                        staging = D3D.CreateTexture(device, w, h, true);
                        surface = D3D.Surface(texture);
                        texW = w;
                        texH = h;
                        pixels = new byte[w * h * 4];
                    }
                    player.CopyFrameToVideoSurface(surface);
                    D3D.Read(context, texture, staging, texW, texH, pixels);
                    pending = true;
                }
                catch (Exception ex)
                {
                    Fail("drawing the picture: " + ex.Message);
                    return;
                }
            }
            ui.BeginInvoke(DispatcherPriority.Render, new Action(Show));
        }

        void Show()
        {
            lock (gate)
            {
                pending = false;
                if (disposed || pixels == null) return;
                if (bitmap == null || bitmap.PixelWidth != texW || bitmap.PixelHeight != texH)
                    bitmap = new WriteableBitmap(texW, texH, 96, 96, System.Windows.Media.PixelFormats.Bgr32, null);
                bitmap.WritePixels(new Int32Rect(0, 0, texW, texH), pixels, texW * 4, 0);
            }
            if (FrameShown != null) FrameShown();
        }

        void FreeTextures()
        {
            if (surface != null) { Marshal.ReleaseComObject(surface); surface = null; }
            if (texture != IntPtr.Zero) { Marshal.Release(texture); texture = IntPtr.Zero; }
            if (staging != IntPtr.Zero) { Marshal.Release(staging); staging = IntPtr.Zero; }
            texW = texH = 0;
        }

        public void Dispose()
        {
            MediaPlayer p;
            lock (gate)
            {
                if (disposed) return;
                disposed = true;
                p = player;
                player = null;
            }
            if (p != null)
            {
                try { p.Pause(); } catch (Exception) { }
                p.IsVideoFrameServerEnabled = false;
                p.Source = null;
                p.Dispose();
            }
            if (adaptive != null) { adaptive.Dispose(); adaptive = null; }
            lock (gate)
            {
                FreeTextures();
                if (context != IntPtr.Zero) { Marshal.Release(context); context = IntPtr.Zero; }
                if (device != IntPtr.Zero) { Marshal.Release(device); device = IntPtr.Zero; }
                pixels = null;
            }
        }

        /// <summary>The bit of Direct3D 11 the frame server needs, through the COM vtables.</summary>
        static class D3D
        {
            [DllImport("d3d11.dll")]
            static extern int D3D11CreateDevice(IntPtr adapter, int driverType, IntPtr software, uint flags, IntPtr levels, uint levelCount,
                uint sdkVersion, out IntPtr device, out int level, out IntPtr context);

            [DllImport("d3d11.dll")]
            static extern int CreateDirect3D11SurfaceFromDXGISurface(IntPtr dxgiSurface, out IntPtr inspectable);

            [StructLayout(LayoutKind.Sequential)]
            struct TextureDesc
            {
                public uint Width, Height, MipLevels, ArraySize, Format, SampleCount, SampleQuality, Usage, BindFlags, CpuAccessFlags, MiscFlags;
            }

            [StructLayout(LayoutKind.Sequential)]
            struct Mapped
            {
                public IntPtr Data;
                public uint RowPitch, DepthPitch;
            }

            delegate int CreateTexture2DFn(IntPtr self, ref TextureDesc desc, IntPtr initial, out IntPtr texture);
            delegate void CopyResourceFn(IntPtr self, IntPtr dst, IntPtr src);
            delegate int MapFn(IntPtr self, IntPtr resource, uint subresource, int mapType, uint flags, out Mapped mapped);
            delegate void UnmapFn(IntPtr self, IntPtr resource, uint subresource);
            delegate int SetMultithreadProtectedFn(IntPtr self, int on);

            // Vtable slots: ID3D11Device::CreateTexture2D 5; ID3D11DeviceContext::Map 14, Unmap 15,
            // CopyResource 47; ID3D10Multithread::SetMultithreadProtected 5.
            static T Method<T>(IntPtr obj, int slot) where T : class
            {
                IntPtr vtable = Marshal.ReadIntPtr(obj);
                return Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(vtable, slot * IntPtr.Size), typeof(T)) as T;
            }

            static readonly Guid IID_ID3D10Multithread = new Guid("9B7E4E00-342C-4106-A19F-4F2704F689F0");
            static readonly Guid IID_IDXGISurface = new Guid("cafcb56c-6ac3-4889-bf47-9e23bbd260ec");

            public static void CreateDevice(out IntPtr device, out IntPtr context)
            {
                int level;
                // D3D_DRIVER_TYPE_HARDWARE; D3D11_CREATE_DEVICE_BGRA_SUPPORT | VIDEO_SUPPORT.
                int hr = D3D11CreateDevice(IntPtr.Zero, 1, IntPtr.Zero, 0x20 | 0x800, IntPtr.Zero, 0, 7, out device, out level, out context);
                if (hr < 0) Marshal.ThrowExceptionForHR(hr);
                Guid iid = IID_ID3D10Multithread;
                IntPtr mt;
                if (Marshal.QueryInterface(device, ref iid, out mt) >= 0)
                {
                    Method<SetMultithreadProtectedFn>(mt, 5)(mt, 1);
                    Marshal.Release(mt);
                }
            }

            public static IntPtr CreateTexture(IntPtr device, int w, int h, bool forReading)
            {
                TextureDesc d = new TextureDesc
                {
                    Width = (uint)w, Height = (uint)h, MipLevels = 1, ArraySize = 1,
                    Format = 87, // DXGI_FORMAT_B8G8R8A8_UNORM
                    SampleCount = 1,
                    Usage = forReading ? 3u : 0u, // STAGING : DEFAULT
                    BindFlags = forReading ? 0u : 0x28u, // RENDER_TARGET | SHADER_RESOURCE
                    CpuAccessFlags = forReading ? 0x20000u : 0u // CPU_ACCESS_READ
                };
                IntPtr t;
                int hr = Method<CreateTexture2DFn>(device, 5)(device, ref d, IntPtr.Zero, out t);
                if (hr < 0) Marshal.ThrowExceptionForHR(hr);
                return t;
            }

            public static IDirect3DSurface Surface(IntPtr texture)
            {
                Guid iid = IID_IDXGISurface;
                IntPtr dxgi, inspectable;
                Marshal.ThrowExceptionForHR(Marshal.QueryInterface(texture, ref iid, out dxgi));
                int hr = CreateDirect3D11SurfaceFromDXGISurface(dxgi, out inspectable);
                Marshal.Release(dxgi);
                if (hr < 0) Marshal.ThrowExceptionForHR(hr);
                object o = Marshal.GetObjectForIUnknown(inspectable);
                Marshal.Release(inspectable);
                return (IDirect3DSurface)o;
            }

            public static void Read(IntPtr context, IntPtr texture, IntPtr staging, int w, int h, byte[] into)
            {
                Method<CopyResourceFn>(context, 47)(context, staging, texture);
                Mapped m;
                int hr = Method<MapFn>(context, 14)(context, staging, 0, 1 /* READ */, 0, out m);
                if (hr < 0) Marshal.ThrowExceptionForHR(hr);
                try
                {
                    int row = w * 4;
                    for (int y = 0; y < h; y++)
                        Marshal.Copy(new IntPtr(m.Data.ToInt64() + (long)y * m.RowPitch), into, y * row, row);
                }
                finally
                {
                    Method<UnmapFn>(context, 15)(context, staging, 0);
                }
            }
        }
    }
}
