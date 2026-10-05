param([Parameter(Mandatory=$true)][string] $ConnectionFile)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class RearCueManagerWindow {
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string cls, string name);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr handle, int command);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr handle);
}
'@
$hash = [System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes([System.IO.Path]::GetFullPath($ConnectionFile)))).Replace('-', '')
$created = $false
$mutex = New-Object System.Threading.Mutex($true, "Local\RearCueManager-$hash", [ref]$created)
if (-not $created) {
    $handle = [RearCueManagerWindow]::FindWindow($null, "RearCue 会话管理")
    if ($handle -ne [IntPtr]::Zero) {
        [void][RearCueManagerWindow]::ShowWindow($handle, 9)
        [void][RearCueManagerWindow]::SetForegroundWindow($handle)
    }
    $mutex.Dispose()
    exit
}

[System.Windows.Forms.Application]::EnableVisualStyles()
$script:rows = @()
$script:selected = @{}
$script:draft = @{}
$script:revision = 0
$script:rendering = $false

function Invoke-Manager([string]$Method, [string]$Path, $Body = $null) {
    $connection = [System.IO.File]::ReadAllText($ConnectionFile) | ConvertFrom-Json
    $request = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$($connection.port)$Path")
    $request.Method = $Method
    $request.Proxy = $null
    $request.Timeout = 15000
    $request.Headers['Authorization'] = "Bearer $($connection.token)"
    if ($null -ne $Body) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 8 -Compress))
        $request.ContentType = 'application/json; charset=utf-8'
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
    }
    try {
        $response = $request.GetResponse()
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream(), [System.Text.Encoding]::UTF8)
            try { $failure = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
            throw $failure.error
        }
        throw "电脑桥暂不可用，请稍后刷新"
    }
    $reader = New-Object System.IO.StreamReader($response.GetResponseStream(), [System.Text.Encoding]::UTF8)
    try { return ($reader.ReadToEnd() | ConvertFrom-Json) } finally { $reader.Dispose(); $response.Dispose() }
}
function Key-For($row) { return "$($row.source)`0$($row.sessionId)" }
function Source-Name([string]$source) {
    switch ($source) { 'codex' { 'Codex' }; 'claude' { 'Claude' }; 'zcode' { 'ZCode' }; 'dsh' { 'DSH' }; default { '其他' } }
}

$form = New-Object System.Windows.Forms.Form
$form.Text = 'RearCue 会话管理'
$form.Size = New-Object System.Drawing.Size(1000, 640)
$form.MinimumSize = New-Object System.Drawing.Size(760, 420)
$form.StartPosition = 'CenterScreen'
$form.Font = New-Object System.Drawing.Font('Microsoft YaHei UI', 9)

$top = New-Object System.Windows.Forms.FlowLayoutPanel
$top.Dock = 'Top'; $top.Height = 46; $top.Padding = New-Object System.Windows.Forms.Padding(10)
$search = New-Object System.Windows.Forms.TextBox
$search.Width = 380
$hint = New-Object System.Windows.Forms.Label
$hint.Text = '搜索标题或目录'; $hint.AutoSize = $true; $hint.Padding = New-Object System.Windows.Forms.Padding(0,4,4,0)
$source = New-Object System.Windows.Forms.ComboBox
$source.DropDownStyle = 'DropDownList'; $source.Width = 100
[void]$source.Items.AddRange(@('全部来源','Codex','Claude','ZCode','DSH')); $source.SelectedIndex = 0
$refresh = New-Object System.Windows.Forms.Button
$refresh.Text = '刷新'; $refresh.AutoSize = $true
$top.Controls.AddRange(@($hint,$search,$source,$refresh))

$bottom = New-Object System.Windows.Forms.Panel
$bottom.Dock = 'Bottom'; $bottom.Height = 85
$status = New-Object System.Windows.Forms.Label
$status.Dock = 'Top'; $status.Height = 27; $status.Padding = New-Object System.Windows.Forms.Padding(10,5,0,0)
$buttons = New-Object System.Windows.Forms.FlowLayoutPanel
$buttons.Dock = 'Bottom'; $buttons.Height = 46; $buttons.Padding = New-Object System.Windows.Forms.Padding(10,5,0,0)
$selectAll = New-Object System.Windows.Forms.Button; $selectAll.Text = '选中当前结果'; $selectAll.Width = 125
$add = New-Object System.Windows.Forms.Button; $add.Text = '加入 / 恢复'; $add.Width = 110
$remove = New-Object System.Windows.Forms.Button; $remove.Text = '移出'; $remove.Width = 80
$apply = New-Object System.Windows.Forms.Button; $apply.Text = '应用'; $apply.Width = 80
$cancel = New-Object System.Windows.Forms.Button; $cancel.Text = '放弃未应用改动'; $cancel.Width = 150
$buttons.Controls.AddRange(@($selectAll,$add,$remove,$apply,$cancel))
$bottom.Controls.AddRange(@($status,$buttons))

$grid = New-Object System.Windows.Forms.DataGridView
$grid.Dock = 'Fill'; $grid.AllowUserToAddRows = $false; $grid.AllowUserToDeleteRows = $false
$grid.RowHeadersVisible = $false; $grid.BackgroundColor = [System.Drawing.SystemColors]::Window
$grid.AutoSizeColumnsMode = 'Fill'; $grid.SelectionMode = 'FullRowSelect'; $grid.MultiSelect = $true
$check = New-Object System.Windows.Forms.DataGridViewCheckBoxColumn
$check.Name = 'selected'; $check.HeaderText = '选择'; $check.FillWeight = 13
[void]$grid.Columns.Add($check)
foreach ($column in @(@('title','会话标题',70),@('source','来源',20),@('workspace','目录',85),@('state','镜像名单',25))) {
    $c = New-Object System.Windows.Forms.DataGridViewTextBoxColumn
    $c.Name = $column[0]; $c.HeaderText = $column[1]; $c.FillWeight = $column[2]; $c.ReadOnly = $true
    [void]$grid.Columns.Add($c)
}
$form.Controls.AddRange(@($grid,$top,$bottom))

function Update-Status([string]$message = '') {
    $apply.Enabled = $script:draft.Count -gt 0
    $status.Text = "已选择 $($script:selected.Count) 条 · 待应用 $($script:draft.Count) 条。关闭窗口将放弃未应用改动。 $message"
}
function Render-Rows {
    $script:rendering = $true
    $grid.Rows.Clear()
    $query = $search.Text.Trim()
    foreach ($row in $script:rows) {
        $name = Source-Name $row.source
        if ($source.SelectedIndex -ne 0 -and $source.SelectedItem -ne $name) { continue }
        if ($query -and "$($row.title) $($row.workspace)".IndexOf($query,[System.StringComparison]::OrdinalIgnoreCase) -lt 0) { continue }
        $key = Key-For $row
        $state = switch ($row.state) { 'enabled' { '已加入' }; 'removed' { '已移出' }; default { '可加入' } }
        if ($script:draft.ContainsKey($key)) { $state = if ($script:draft[$key].enabled) { '待加入 / 恢复' } else { '待移出' } }
        if (-not $row.available) { $state += ' · 来源不可用' }
        $index = $grid.Rows.Add([bool]$script:selected[$key], $row.title, $name, $row.workspace, $state)
        $grid.Rows[$index].Tag = $row
    }
    $script:rendering = $false
    Update-Status
}
function Load-Rows {
    $form.UseWaitCursor = $true
    try {
        $snapshot = Invoke-Manager 'GET' '/sessions'
        if ($snapshot.error) { throw $snapshot.error }
        $script:rows = @($snapshot.sessions)
        $script:revision = $snapshot.revision
        Render-Rows
        if ($snapshot.failures.PSObject.Properties.Count) { Update-Status '部分来源暂不可读，请稍后刷新。' }
    } catch { Update-Status ([string]$_) }
    finally { $form.UseWaitCursor = $false }
}
function Stage-Selected([bool]$enabled) {
    foreach ($row in $script:rows) {
        $key = Key-For $row
        if (-not $script:selected[$key]) { continue }
        if ($enabled -and -not $row.available) { Update-Status '来源不可用的会话暂不能加入，请刷新。'; return }
    }
    foreach ($row in $script:rows) {
        $key = Key-For $row
        if ($script:selected[$key]) { $script:draft[$key] = @{source=$row.source;sessionId=$row.sessionId;enabled=$enabled} }
    }
    Render-Rows
}
$search.add_TextChanged({ Render-Rows })
$source.add_SelectedIndexChanged({ Render-Rows })
$refresh.add_Click({ Load-Rows })
$grid.add_CurrentCellDirtyStateChanged({ if ($grid.IsCurrentCellDirty) { [void]$grid.CommitEdit([System.Windows.Forms.DataGridViewDataErrorContexts]::Commit) } })
$grid.add_CellValueChanged({
    param($sender,$event)
    if ($script:rendering -or $event.RowIndex -lt 0 -or $event.ColumnIndex -ne 0) { return }
    $row = $grid.Rows[$event.RowIndex]
    $key = Key-For $row.Tag
    if ($row.Cells[0].Value) { $script:selected[$key] = $true } else { $script:selected.Remove($key) }
    Update-Status
})
$selectAll.add_Click({ foreach ($row in $grid.Rows) { $row.Cells[0].Value = $true } })
$add.add_Click({ Stage-Selected $true })
$remove.add_Click({ Stage-Selected $false })
$cancel.add_Click({ $script:draft = @{}; Render-Rows })
$apply.add_Click({
    $form.UseWaitCursor = $true
    try {
        $result = Invoke-Manager 'POST' '/apply' @{revision=$script:revision;changes=@($script:draft.Values)}
        if (-not $result.ok) { throw '名单未保存' }
        $script:draft = @{}; $script:selected = @{}
        Load-Rows
        Update-Status '已应用，手机名单将同步更新。'
    } catch { Update-Status ([string]$_) }
    finally { $form.UseWaitCursor = $false }
})
$form.add_Shown({ Load-Rows })
try { [System.Windows.Forms.Application]::Run($form) }
finally { $form.Dispose(); $mutex.ReleaseMutex(); $mutex.Dispose() }
