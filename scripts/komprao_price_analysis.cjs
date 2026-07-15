// Analisa o relatorio gerado por komprao_price_report.cjs:
// entende as regras de preco retornadas pela API On-Way/Komprao,
// conta tipos/nomes de regra, e aponta sempre o melhor preco por produto.
// Uso: node scripts/komprao_price_analysis.cjs [caminho_relatorio.json]

const fs = require("fs");
const path = require("path");

const reportPath = process.argv[2] || path.join(__dirname, "komprao_price_report.json");
const report = JSON.parse(fs.readFileSync(reportPath, "utf8"));

const PRICE_LABELS = {
  pdv: "Preço PDV (preço de balcão/exposto)",
  normal: "Preço normal (tabela, sem desconto)",
  atacado: "Preço atacado (compra em quantidade)",
  clube_koch: "Preço Clube Koch (cliente cadastrado no clube)",
  leve_pague: "Leve X Pague Y (promoção por quantidade)",
  crm: "Preço CRM (campanha/segmentação de cliente)",
};

function melhorPreco(precos) {
  let melhorChave = null;
  let melhorValor = Infinity;
  for (const [chave, valor] of Object.entries(precos)) {
    if (valor == null) continue;
    if (valor < melhorValor) {
      melhorValor = valor;
      melhorChave = chave;
    }
  }
  if (melhorChave == null) return null;
  return { chave: melhorChave, nome: PRICE_LABELS[melhorChave], valor: melhorValor };
}

const dinamicas = new Map(); // "tipo|descricao" -> count
const melhorOpcaoCount = new Map(); // chave -> count
const produtosAnalisados = [];

for (const p of report.produtos) {
  if (p.erro) continue;
  const dinKey = `${p.tipo_dinamica}|${p.descricao_dinamica}`;
  dinamicas.set(dinKey, (dinamicas.get(dinKey) || 0) + 1);

  const melhor = melhorPreco(p.precos);
  if (melhor) {
    melhorOpcaoCount.set(melhor.chave, (melhorOpcaoCount.get(melhor.chave) || 0) + 1);
  }

  const precoReferencia = p.precos.normal ?? p.precos.pdv;
  const economia = melhor && precoReferencia != null ? +(precoReferencia - melhor.valor).toFixed(2) : 0;
  const economiaPct = melhor && precoReferencia ? +((economia / precoReferencia) * 100).toFixed(1) : 0;

  produtosAnalisados.push({
    ean: p.ean,
    sku: p.sku,
    descricao: p.descricao,
    regra_aplicada: p.descricao_dinamica,
    melhor_opcao: melhor ? melhor.nome : null,
    melhor_preco: melhor ? melhor.valor : null,
    preco_tabela_normal: precoReferencia,
    economia_rs: economia,
    economia_pct: economiaPct,
    todos_os_precos: p.precos,
  });
}

const tiposDinamica = Array.from(dinamicas.entries())
  .map(([key, total]) => {
    const [tipo, descricao] = key.split("|");
    return { tipo, descricao, total };
  })
  .sort((a, b) => b.total - a.total);

const melhorOpcaoResumo = Array.from(melhorOpcaoCount.entries())
  .map(([chave, total]) => ({ chave, nome: PRICE_LABELS[chave], total }))
  .sort((a, b) => b.total - a.total);

const analysis = {
  gerado_em: new Date().toISOString(),
  fonte: reportPath,
  entendimento: {
    como_a_api_retorna:
      "A API On-Way (Komprao) devolve, por EAN+loja, um objeto 'data' com varios campos de preco simultaneos " +
      "(PRECO_PDV, PRECO_NORMAL, PRECO_PDV_ATACADO, PRECO_CLUBE_KOCH, PRECO_LEVEPAGUE, PRECO_CRM) mais um array " +
      "'prices' com os precos efetivamente ativos e os campos 'priceDynamicsType'/'priceDynamicsDescription' " +
      "que dizem qual regra de precificacao esta vigente para aquele item naquele momento.",
    campos_de_preco: PRICE_LABELS,
    tipos_de_dinamica_observados:
      "'Normal' = preco de tabela (com ou sem atacado vigente). 'Promotional' = ha desconto ativo, podendo ser " +
      "'Oferta promoção + De X por Y', 'Preço Clube Koch' (so para quem tem o cartao clube) ou leve-pague " +
      "(campo PRECO_LEVEPAGUE, nao observado nesta amostra de 100 itens).",
    regra_de_melhor_preco:
      "Para cada produto, comparamos todos os precos nao-nulos retornados (pdv, normal, atacado, clube, leve-pague, crm) " +
      "e elegemos o menor valor como 'melhor preco' e 'melhor opcao'. A economia e calculada contra o preco normal de tabela.",
  },
  tipos_de_regra_encontrados: tiposDinamica,
  melhor_opcao_mais_frequente: melhorOpcaoResumo,
  resumo: {
    total_produtos: produtosAnalisados.length,
    com_atacado: report.resumo.atacado,
    com_clube: report.resumo.clube,
    com_leve_pague: report.resumo.leve_pague,
    em_promocao: report.resumo.promocao,
    economia_media_pct:
      +(produtosAnalisados.reduce((acc, p) => acc + p.economia_pct, 0) / produtosAnalisados.length).toFixed(1),
  },
  como_avancar: [
    "Implementar no terminal a logica 'melhor preco e melhor opcao' acima: ao consultar um EAN, comparar todos os campos PRECO_* != null e exibir o menor, com selo da regra (ex: 'Clube Koch', 'Atacado', 'Leve X Pague Y').",
    "Adicionar cache de token JWT (login expira) em vez de logar a cada consulta — replicar o padrao ja usado para 'integra-assai' no PriceQueryEngine.kt, guardando token+validade e renovando sob demanda.",
    "Criar uma nova integracao 'integra-komprao' no PriceConfig/PriceQueryEngine com steps: 1) login (token), 2) lookup_price por EAN+loja, mapeando os 6 campos de preco e o priceDynamicsType para a UI.",
    "Mapear o codigo de loja (ex: 18) por terminal/dispositivo, pois o preco e por loja — hoje o script usa um storeId fixo passado por parametro.",
    "Investigar amostras maiores para confirmar como o campo PRECO_LEVEPAGUE se popula (nao apareceu nestes 100 EANs) antes de finalizar a regra de exibicao de 'leve X pague Y'.",
  ],
  produtos: produtosAnalisados,
};

const outPath = path.join(path.dirname(reportPath), "komprao_price_analysis.json");
fs.writeFileSync(outPath, JSON.stringify(analysis, null, 2), "utf8");

console.error("=== Tipos de regra encontrados ===");
for (const t of tiposDinamica) console.error(`${t.tipo} / ${t.descricao}: ${t.total}`);
console.error("\n=== Melhor opcao mais frequente ===");
for (const m of melhorOpcaoResumo) console.error(`${m.nome}: ${m.total}`);
console.error(`\nEconomia media: ${analysis.resumo.economia_media_pct}%`);
console.error(`\nAnalise salva em ${outPath}`);
