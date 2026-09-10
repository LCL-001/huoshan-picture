$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jm = "D:\develop\idea_java_projects\yun-picture-backend\jmeter"

Write-Host "=== 结果文件清单 ==="
Get-ChildItem $jm -File | Where-Object { $_.Extension -in ".jtl",".log",".csv" } |
    Sort-Object LastWriteTime |
    Format-Table Name, @{n="KB";e={[int]($_.Length/1KB)}}, LastWriteTime -AutoSize | Out-String | Write-Host

Write-Host "=== 报告目录 ==="
Get-ChildItem $jm -Directory | Where-Object { $_.Name -match "report|smoke" } |
    Sort-Object LastWriteTime |
    ForEach-Object {
        $sj = Join-Path $_.FullName "statistics.json"
        $flag = if (Test-Path $sj) { "有statistics.json" } else { "" }
        "{0,-22} {1} {2}" -f $_.Name, $_.LastWriteTime, $flag
    }
