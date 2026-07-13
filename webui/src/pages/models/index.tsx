import { useEffect } from 'react';
import { fetchModels } from '@entities/models';
import { ModelCatalog } from '@widgets/model-catalog';

export function ModelsPage() {
  useEffect(() => {
    void fetchModels();
  }, []);
  return <ModelCatalog />;
}
