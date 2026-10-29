; Inno Setup script for vKNX. Built by the release workflow:  iscc /DAppVersion=0.2.0 /DSourceDir=dist\app installer\vKNX.iss
#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist\app"
#endif

[Setup]
AppId={{6F0C3A1E-5B1D-4C55-9C7A-2B7B6D0C5E11}
AppName=vKNX
AppVersion={#AppVersion}
AppPublisher=Marc Sonne Dahl
AppPublisherURL=https://github.com/MrDahl/vKNX
DefaultDirName={localappdata}\Programs\vKNX
DefaultGroupName=vKNX
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=vKNX-Setup-{#AppVersion}
SetupIconFile=..\launcher\penguin.ico
UninstallDisplayIcon={app}\vKNX.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
CloseApplicationsFilter=*.exe
RestartApplications=no
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\vKNX"; Filename: "{app}\vKNX.exe"
Name: "{autodesktop}\vKNX"; Filename: "{app}\vKNX.exe"; Tasks: desktopicon

[Run]
; interactive install: offer to start; silent install (automatic update): always start the new version
Filename: "{app}\vKNX.exe"; Description: "{cm:LaunchProgram,vKNX}"; Flags: nowait postinstall skipifsilent
Filename: "{app}\vKNX.exe"; Flags: nowait; Check: WizardSilent
