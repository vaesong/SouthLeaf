# Windows Credential Manager is only a local launcher concern. The Java application uses environment variables.
if (-not ('SouthLeaf.CredentialStore' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace SouthLeaf {
 public static class CredentialStore {
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
  private struct Credential {
   public uint Flags; public uint Type; public string TargetName; public string Comment;
   public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
   public uint CredentialBlobSize; public IntPtr CredentialBlob; public uint Persist;
   public uint AttributeCount; public IntPtr Attributes; public string TargetAlias; public string UserName;
  }
  [DllImport("advapi32.dll", EntryPoint="CredWriteW", CharSet=CharSet.Unicode, SetLastError=true)]
  private static extern bool CredWrite(ref Credential credential, uint flags);
  [DllImport("advapi32.dll", EntryPoint="CredReadW", CharSet=CharSet.Unicode, SetLastError=true)]
  private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);
  [DllImport("advapi32.dll")] private static extern void CredFree(IntPtr credential);
  public static void Save(string target, string username, string password) {
   IntPtr secret=Marshal.StringToCoTaskMemUni(password);
   try {
    var c=new Credential { Type=1, TargetName=target, UserName=username,
     CredentialBlob=secret, CredentialBlobSize=(uint)(password.Length*2), Persist=2 };
    if(!CredWrite(ref c,0)) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
   } finally { Marshal.ZeroFreeCoTaskMemUnicode(secret); }
  }
  public static string[] Read(string target) {
   IntPtr pointer;
   if(!CredRead(target,1,0,out pointer)) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
   try { var c=(Credential)Marshal.PtrToStructure(pointer,typeof(Credential));
    return new string[] { c.UserName, Marshal.PtrToStringUni(c.CredentialBlob,(int)c.CredentialBlobSize/2) };
   } finally { CredFree(pointer); }
  }
 }
}
'@
}
function Get-SouthLeafLocalCredential([string]$Role) {
    $entry = [SouthLeaf.CredentialStore]::Read("SouthLeaf/PublicDatabase/$Role")
    return New-Object System.Management.Automation.PSCredential($entry[0], (ConvertTo-SecureString -String $entry[1] -AsPlainText -Force))
}
function Get-SouthLeafLocalUrl([string]$Schema) {
    return "jdbc:mysql://127.0.0.1:3306/${Schema}?sslMode=REQUIRED&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
}

function Get-SouthLeafLocalMediaRoot {
    if ($env:SOUTHLEAF_MEDIA_ROOT) { return $env:SOUTHLEAF_MEDIA_ROOT }
    $repository = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
    return Join-Path ($repository + '-data') 'public-media'
}
