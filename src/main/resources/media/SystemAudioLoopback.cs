using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace MiniMeeting {
    // Process loopback excludes the Java process tree, including meeting playout.
    // Windows 10 build 20348+ / Windows 11. Never fall back to microphone capture.
    public static class SystemAudioLoopback {
        [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioClient {
            [PreserveSig] int Initialize(int mode,uint flags,long duration,long period,ref Format format,IntPtr session);
            [PreserveSig] int GetBufferSize(out uint frames);
            [PreserveSig] int GetStreamLatency(out long latency);
            [PreserveSig] int GetCurrentPadding(out uint frames);
            [PreserveSig] int IsFormatSupported(int mode,IntPtr format,out IntPtr closest);
            [PreserveSig] int GetMixFormat(out IntPtr format);
            [PreserveSig] int GetDevicePeriod(out long normal,out long minimum);
            [PreserveSig] int Start();
            [PreserveSig] int Stop();
            [PreserveSig] int Reset();
            [PreserveSig] int SetEventHandle(IntPtr handle);
            [PreserveSig] int GetService(ref Guid iid,[MarshalAs(UnmanagedType.IUnknown)] out object service);
        }
        [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface ICaptureClient {
            [PreserveSig] int GetBuffer(out IntPtr data,out uint frames,out uint flags,out ulong position,out ulong timestamp);
            [PreserveSig] int ReleaseBuffer(uint frames);
            [PreserveSig] int GetNextPacketSize(out uint frames);
        }
        [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        public interface IActivation {
            [PreserveSig] int GetActivateResult(out int result,[MarshalAs(UnmanagedType.IUnknown)] out object instance);
        }
        [ComVisible(true), Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        public interface ICompletion { [PreserveSig] int ActivateCompleted(IActivation operation); }
        [ComVisible(true), Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        public interface IAgile { }
        [ComVisible(true), ClassInterface(ClassInterfaceType.None)]
        public sealed class Completion : ICompletion,IAgile {
            internal readonly ManualResetEvent Done=new ManualResetEvent(false);
            internal object Client;
            internal int Result=unchecked((int)0x80004005);
            public int ActivateCompleted(IActivation operation) {
                try {int call=operation.GetActivateResult(out Result,out Client);if(call<0) Result=call;}
                catch {Result=unchecked((int)0x80004005);}
                finally {Done.Set();}
                return 0;
            }
        }
        [StructLayout(LayoutKind.Sequential,Pack=2)]
        struct Format {public ushort Tag,Channels;public uint Rate,BytesPerSecond;public ushort Align,Bits,Extra;}
        [StructLayout(LayoutKind.Sequential)]
        struct ActivationParams {public int Type;public uint Process;public int Mode;}
        [StructLayout(LayoutKind.Explicit,Size=24)]
        struct Variant {
            [FieldOffset(0)] public ushort Type;
            [FieldOffset(8)] public uint Size;
            [FieldOffset(16)] public IntPtr Data;
        }
        [DllImport("Mmdevapi.dll",CharSet=CharSet.Unicode,ExactSpelling=true)]
        static extern int ActivateAudioInterfaceAsync(string path,ref Guid iid,ref Variant parameters,ICompletion completion,out IActivation operation);
        [DllImport("ole32.dll")] static extern int CoInitializeEx(IntPtr reserved,uint mode);
        [DllImport("ole32.dll")] static extern void CoUninitialize();
        static void Check(int hr) {if(hr<0) Marshal.ThrowExceptionForHR(hr);}
        static volatile bool stopped;
        public static void Run(uint process) {
            if(IntPtr.Size!=8 || Environment.OSVersion.Version.Build<20348) throw new NotSupportedException();
            var worker=new Thread(delegate() {Capture(process);});worker.SetApartmentState(ApartmentState.MTA);
            var stopReader=new Thread(delegate() {try {Console.ReadLine();} catch {} stopped=true;});
            stopReader.IsBackground=true;stopReader.Start();worker.Start();worker.Join();
        }
        static void Capture(uint process) {
            int initialized=CoInitializeEx(IntPtr.Zero,0);
            IAudioClient client=null;ICaptureClient capture=null;IActivation operation=null;
            var completion=new Completion();
            IntPtr data=IntPtr.Zero;
            try {
                Check(initialized);
                var parameters=new ActivationParams {Type=1,Process=process,Mode=1}; // EXCLUDE_TARGET_PROCESS_TREE
                data=Marshal.AllocHGlobal(Marshal.SizeOf(parameters));Marshal.StructureToPtr(parameters,data,false);
                var variant=new Variant {Type=65,Size=(uint)Marshal.SizeOf(parameters),Data=data}; // VT_BLOB
                Guid iid=new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
                Check(ActivateAudioInterfaceAsync("VAD\\Process_Loopback",ref iid,ref variant,completion,out operation));
                if(!completion.Done.WaitOne(8000)) throw new TimeoutException();
                Check(completion.Result);client=(IAudioClient)completion.Client;
                var format=new Format {Tag=1,Channels=2,Rate=48000,BytesPerSecond=192000,Align=4,Bits=16,Extra=0};
                Check(client.Initialize(0,0x80060000,0,0,ref format,IntPtr.Zero));
                Guid captureId=new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");object service;
                Check(client.GetService(ref captureId,out service));capture=(ICaptureClient)service;
                using(var ready=new AutoResetEvent(false)) {
                    Check(client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle()));Check(client.Start());
                    using(Stream output=Console.OpenStandardOutput()) {
                        byte[] magic=System.Text.Encoding.ASCII.GetBytes("MMRSA01\n");output.Write(magic,0,magic.Length);output.Flush();
                        byte[] block=new byte[1920];int used=0;
                        while(!stopped) {
                            ready.WaitOne(100);
                            uint next;Check(capture.GetNextPacketSize(out next));
                            while(next>0 && !stopped) {
                                IntPtr buffer;uint frames,flags;ulong position,time;
                                Check(capture.GetBuffer(out buffer,out frames,out flags,out position,out time));
                                try {
                                    if(frames>48000) throw new InvalidOperationException();
                                    int bytes=checked((int)frames*4),offset=0;
                                    while(offset<bytes) {
                                        int count=Math.Min(bytes-offset,block.Length-used);
                                        if((flags&2)!=0) Array.Clear(block,used,count);
                                        else Marshal.Copy(IntPtr.Add(buffer,offset),block,used,count);
                                        used+=count;offset+=count;
                                        if(used==block.Length) {output.Write(block,0,block.Length);output.Flush();used=0;}
                                    }
                                } finally {Check(capture.ReleaseBuffer(frames));}
                                Check(capture.GetNextPacketSize(out next));
                            }
                        }
                    }
                }
            } catch {Environment.ExitCode=2;} // No device descriptors, PCM, or exception text in logs.
            finally {
                if(client!=null) {try {client.Stop();} catch {}}
                if(capture!=null) Marshal.ReleaseComObject(capture);
                if(client!=null) Marshal.ReleaseComObject(client);
                if(operation!=null) Marshal.ReleaseComObject(operation);
                if(data!=IntPtr.Zero) Marshal.FreeHGlobal(data);
                completion.Done.Dispose();if(initialized>=0) CoUninitialize();
            }
        }
    }
}
