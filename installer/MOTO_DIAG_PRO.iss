#define MyAppName "MOTO DIAG PRO"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "EFE MOTO"
#define MyAppExeName "MOTO DIAG PRO.exe"

[Setup]
AppId={{7A13F0D5-8D31-4C83-8E19-9B0B65D2B1A1}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\MOTO DIAG PRO
DefaultGroupName={#MyAppName}
OutputDir=..\release
OutputBaseFilename=MOTO-DIAG-PRO-Setup-x64
ArchitecturesInstallIn64BitMode=x64
ArchitecturesAllowed=x64
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
UninstallDisplayIcon={app}\{#MyAppExeName}

[Languages]
Name: "turkish"; MessagesFile: "compiler:Languages\Turkish.isl"

[Tasks]
Name: "desktopicon"; Description: "Masaüstüne kısayol oluştur"; GroupDescription: "Ek görevler:"

[Files]
Source: "..\dist\MOTO DIAG PRO\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "redist\vc_redist.x64.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{tmp}\vc_redist.x64.exe"; Parameters: "/install /quiet /norestart"; StatusMsg: "Gerekli Windows çalışma kitaplıkları kuruluyor..."; Flags: waituntilterminated; Check: not IsVCRedistInstalled
Filename: "{app}\{#MyAppExeName}"; Description: "MOTO DIAG PRO'yu başlat"; Flags: postinstall nowait skipifsilent

[Code]
function IsVCRedistInstalled: Boolean;
var
  Installed: Cardinal;
begin
  Result := RegQueryDWordValue(HKLM64,
    'SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64',
    'Installed', Installed) and (Installed = 1);
end;
