// Read-only Windows Core Audio session probe for the opt-in physical playback test.
using System;
using System.Runtime.InteropServices;
public class AudioMixerRead {
 [ComImport,Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class Enumerator {}
 [ComImport,Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IEnum {[PreserveSig] int EnumEndpoints(int f,int s,out IntPtr p);[PreserveSig] int GetDefaultAudioEndpoint(int f,int r,out IDevice d);[PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)]string id,out IDevice d);}
 [ComImport,Guid("D666063F-1587-4E43-81F1-B948E807363F"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IDevice {[PreserveSig] int Activate(ref Guid i,int c,IntPtr p,[MarshalAs(UnmanagedType.IUnknown)]out object o);}
 [ComImport,Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IManager {[PreserveSig] int GetAudioSessionControl(IntPtr g,uint f,out IntPtr c);[PreserveSig] int GetSimpleAudioVolume(IntPtr g,uint f,out IntPtr v);[PreserveSig] int GetSessionEnumerator(out ISessions e);}
 [ComImport,Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface ISessions {[PreserveSig] int GetCount(out int n);[PreserveSig] int GetSession(int i,out IControl c);}
 [ComImport,Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IControl {
 [PreserveSig] int GetState(out int s);[PreserveSig] int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)]out string n);[PreserveSig] int SetDisplayName(string n,IntPtr g);[PreserveSig] int GetIconPath(out IntPtr p);[PreserveSig] int SetIconPath(string p,IntPtr g);[PreserveSig] int GetGroupingParam(out Guid g);[PreserveSig] int SetGroupingParam(ref Guid g,IntPtr c);[PreserveSig] int RegisterAudioSessionNotification(IntPtr p);[PreserveSig] int UnregisterAudioSessionNotification(IntPtr p);[PreserveSig] int GetSessionIdentifier(out IntPtr p);[PreserveSig] int GetSessionInstanceIdentifier(out IntPtr p);[PreserveSig] int GetProcessId(out uint p);
 }
 [ComImport,Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)] interface IVolume {[PreserveSig] int SetMasterVolume(float v,IntPtr g);[PreserveSig] int GetMasterVolume(out float v);[PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)]bool m,IntPtr g);[PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)]out bool m);}
 public static void Run(uint target, string endpoint) {
 IDevice d; Marshal.ThrowExceptionForHR(((IEnum)new Enumerator()).GetDevice(endpoint,out d));
 Guid id=typeof(IManager).GUID;object o;Marshal.ThrowExceptionForHR(d.Activate(ref id,23,IntPtr.Zero,out o));
 ISessions e;Marshal.ThrowExceptionForHR(((IManager)o).GetSessionEnumerator(out e));int n;e.GetCount(out n);
 for(int i=0;i<n;i++){IControl c;e.GetSession(i,out c);uint pid;c.GetProcessId(out pid);if(pid!=target)continue;int state;c.GetState(out state);var volume=(IVolume)c;float level;bool mute;volume.GetMasterVolume(out level);volume.GetMute(out mute);Console.WriteLine("pid="+pid+" state="+state+" app_volume="+level+" app_muted="+mute);}
 }
}
