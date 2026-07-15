// Gera relatorio de tipos de preco (Komprao / On-Way API) para os EANs do CSV.
// Uso: node scripts/komprao_price_report.js [caminhoCsv] [storeId] [limite]

const fs = require("fs");
const path = require("path");

const API_BASE = "https://apionway.superkoch.com.br/on-way";
const USERNAME = "MUPA.APP";
const PASSWORD = "vRTz834/";

const csvPath = process.argv[2] || "C:\\Users\\adria\\Downloads\\ProdutosEAN.csv";
const storeId = process.argv[3] || "18";
const limit = parseInt(process.argv[4] || "100", 10);

function parseCsv(text) {
  const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0);
  const header = lines[0].split(";").map((h) => h.replace(/"/g, "").trim());
  const rows = [];
  for (let i = 1; i < lines.length; i++) {
    const cols = lines[i].split(";").map((c) => c.replace(/"/g, "").trim());
    const row = {};
    header.forEach((h, idx) => (row[h] = cols[idx] ?? ""));
    rows.push(row);
  }
  return rows;
}

async function login() {
  const resp = await fetch(`${API_BASE}/auth/login`, {
    method: "POST",
    headers: { accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({ username: USERNAME, password: PASSWORD }),
  });
  const json = await resp.json();
  if (!json?.data?.token) throw new Error("Falha no login: " + JSON.stringify(json));
  return json.data.token;
}

async function queryEanSingle(ean, token) {
  const resp = await fetch(`${API_BASE}/product/ean/${ean}/store/${storeId}`, {
    headers: { accept: "application/json", Authorization: `Bearer ${token}` },
  });
  if (!resp.ok) return { ean, error: `http_${resp.status}` };
  const json = await resp.json();
  if (!json?.data) return { ean, error: json?.message || "sem_dados" };
  return { ean, data: json.data };
}

async function queryEan(rawCell, token) {
  const candidates = rawCell.split(/\s+/).filter(Boolean);
  let lastError = "sem_dados";
  for (const ean of candidates) {
    const res = await queryEanSingle(ean, token);
    if (!res.error) return res;
    lastError = res.error;
  }
  return { ean: candidates[0] || rawCell, error: lastError };
}

function classify(data) {
  return {
    sku: data.sku,
    descricao: data.description,
    tipo_dinamica: data.priceDynamicsType,
    descricao_dinamica: data.priceDynamicsDescription,
    precos: {
      pdv: data.PRECO_PDV,
      normal: data.PRECO_NORMAL,
      atacado: data.PRECO_PDV_ATACADO,
      clube_koch: data.PRECO_CLUBE_KOCH,
      leve_pague: data.PRECO_LEVEPAGUE,
      crm: data.PRECO_CRM,
    },
    tem_atacado: data.PRECO_PDV_ATACADO != null && data.PRECO_PDV_ATACADO !== data.PRECO_PDV,
    tem_clube: data.PRECO_CLUBE_KOCH != null,
    tem_leve_pague: data.PRECO_LEVEPAGUE != null,
    em_promocao: data.priceDynamicsType === "Promotional",
  };
}

async function main() {
  const csvText = fs.readFileSync(csvPath, "latin1");
  const rows = parseCsv(csvText).slice(0, limit);
  console.error(`Lidos ${rows.length} EANs de ${csvPath}`);

  const token = await login();
  console.error("Login OK");

  const results = [];
  const summary = { total: 0, sucesso: 0, erro: 0, normal: 0, atacado: 0, clube: 0, leve_pague: 0, promocao: 0 };

  for (const row of rows) {
    const ean = (row.EAN || "").trim();
    if (!ean) continue;
    summary.total++;
    const res = await queryEan(ean, token);
    if (res.error) {
      summary.erro++;
      results.push({ ean, erro: res.error, descricao_csv: row.DESCCOMPLETA });
      console.error(`ERRO ean=${ean}: ${res.error}`);
      continue;
    }
    summary.sucesso++;
    const classified = classify(res.data);
    if (classified.tem_atacado) summary.atacado++;
    if (classified.tem_clube) summary.clube++;
    if (classified.tem_leve_pague) summary.leve_pague++;
    if (classified.em_promocao) summary.promocao++;
    if (classified.tipo_dinamica === "Normal") summary.normal++;
    results.push({ ean, ...classified });
  }

  const report = { gerado_em: new Date().toISOString(), loja: storeId, resumo: summary, produtos: results };
  const outPath = path.join(__dirname, "komprao_price_report.json");
  fs.writeFileSync(outPath, JSON.stringify(report, null, 2), "utf8");
  console.error(`Relatorio salvo em ${outPath}`);
  console.error(JSON.stringify(summary, null, 2));
}

main().catch((err) => {
  console.error("Falha no relatorio:", err);
  process.exit(1);
});
