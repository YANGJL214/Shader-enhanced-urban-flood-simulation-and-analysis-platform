import { t } from '@/i18n';

export default function useModel(opt) {
  const { MxMsg } = opt.proxy;

  const downloadExample = async (fileName) => {
    const url = `./model/${fileName}`;

    try {
      const response = await fetch(url);
      if (!response.ok) MxMsg.error(t('message.downloadFailed'));

      const blob = await response.blob();
      const link = document.createElement('a');
      const blobUrl = window.URL.createObjectURL(blob);

      link.href = blobUrl;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(blobUrl);

      MxMsg.success(t('message.zipDownloaded'));
    } catch (err) {
      console.error(err);
      MxMsg.error(t('message.downloadRetry'));
    }
  };

  return {
    downloadExample
  };
}
