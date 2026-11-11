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

#ifdef WebView2Setup
; Microsoft's WebView2 "Evergreen Bootstrapper" (small, downloads the runtime from Microsoft). Only used when the PC does not have the runtime.
Source: "{#WebView2Setup}"; DestDir: "{tmp}"; DestName: "MicrosoftEdgeWebview2Setup.exe"; Flags: deleteafterinstall; Check: NeedsWebView2
#endif

[Icons]
Name: "{autoprograms}\vKNX"; Filename: "{app}\vKNX.exe"
Name: "{autodesktop}\vKNX"; Filename: "{app}\vKNX.exe"; Tasks: desktopicon

[Run]
#ifdef WebView2Setup
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Installing Microsoft WebView2 Runtime (the vKNX window needs it) ..."; Flags: waituntilterminated; Check: NeedsWebView2
#endif
; interactive install: offer to start; silent install (automatic update): always start the new version
Filename: "{app}\vKNX.exe"; Description: "{cm:LaunchProgram,vKNX}"; Flags: nowait postinstall skipifsilent
Filename: "{app}\vKNX.exe"; Flags: nowait; Check: WizardSilent

[Code]
{ Is the Microsoft WebView2 runtime installed (for all users in either registry view, or for this user)? }
function WebView2Version(const Root: Integer; const Key: String): String;
begin
  if not RegQueryStringValue(Root, Key, 'pv', Result) then Result := '';
end;

function NeedsWebView2: Boolean;
var Key, V: String;
begin
  Key := 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';
  V := WebView2Version(HKLM32, Key);
  if (V = '') or (V = '0.0.0.0') then V := WebView2Version(HKLM64, Key);
  if (V = '') or (V = '0.0.0.0') then V := WebView2Version(HKCU, Key);
  Result := (V = '') or (V = '0.0.0.0');
end;

{ Update fix: an installer started by the program itself could not replace files because vKNX.exe or the server
  were still running. Ask the launcher to close (it saves the server), wait, and only then end what is left. }
function IsRunning(const Name: String): Boolean;
var R: Integer;
begin
  Result := Exec(ExpandConstant('{cmd}'), '/C tasklist /FI "IMAGENAME eq ' + Name + '" | find /I "' + Name + '"',
    '', SW_HIDE, ewWaitUntilTerminated, R) and (R = 0);
end;

procedure StopProgram(const Name: String; const Graceful: Boolean);
var R, I: Integer;
begin
  if not IsRunning(Name) then Exit;
  if Graceful then
  begin
    Exec(ExpandConstant('{sys}\taskkill.exe'), '/IM ' + Name, '', SW_HIDE, ewWaitUntilTerminated, R);
    for I := 1 to 20 do
    begin
      if not IsRunning(Name) then Exit;
      Sleep(500);
    end;
  end
  else
    for I := 1 to 6 do
    begin
      if not IsRunning(Name) then Exit;
      Sleep(500);
    end;
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /T /IM ' + Name, '', SW_HIDE, ewWaitUntilTerminated, R);
  Sleep(500);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopProgram('vKNX.exe', True);
  StopProgram('vKNX-Server.exe', False);
  Result := '';
end;
