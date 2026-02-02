import 'dotenv/config';

export interface MetagraphEnv {
  globalL0Url: string;
  node1ML0: string;
  node2ML0: string;
  node3ML0: string;
  node1DataL1: string;
  node2DataL1: string;
  node3DataL1: string;
}

export type TargetEnv = 'local' | 'remote' | 'ci';

export default function getMetagraphEnv(target: TargetEnv | string): MetagraphEnv {
  console.log(`\x1b[33m[metagraphEnv]\x1b[36m Target environment:\x1b[0m ${target}`);

  const demoUrls = JSON.parse(process.env.DEMO_URLS || '{}');
  const ciUrls = JSON.parse(process.env.CI_URLS || '{}');

  const envMappings: Record<string, MetagraphEnv> = {
    remote: {
      globalL0Url: demoUrls.global_l0,
      node1ML0: demoUrls.node_1_ml0,
      node2ML0: demoUrls.node_2_ml0,
      node3ML0: demoUrls.node_3_ml0,
      node1DataL1: demoUrls.node_1_data_l1,
      node2DataL1: demoUrls.node_2_data_l1,
      node3DataL1: demoUrls.node_3_data_l1,
    },
    ci: {
      globalL0Url: ciUrls.global_l0,
      node1ML0: ciUrls.node_1_ml0,
      node2ML0: ciUrls.node_2_ml0,
      node3ML0: ciUrls.node_3_ml0,
      node1DataL1: ciUrls.node_1_data_l1,
      node2DataL1: ciUrls.node_2_data_l1,
      node3DataL1: ciUrls.node_3_data_l1,
    },
    local: {
      globalL0Url: 'http://127.0.0.1:9000',
      node1ML0: 'http://127.0.0.1:9200',
      node2ML0: 'http://127.0.0.1:9200',
      node3ML0: 'http://127.0.0.1:9200',
      node1DataL1: 'http://127.0.0.1:9400',
      node2DataL1: 'http://127.0.0.1:9410',
      node3DataL1: 'http://127.0.0.1:9420',
    },
  };

  return envMappings[target] || envMappings.local;
}
