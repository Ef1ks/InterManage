import { useState } from 'react'
import { Image, Modal, Pressable, ScrollView, StyleSheet, Text, View, useWindowDimensions } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { imageSource } from '@/lib/api'
import { useTheme } from '@/lib/theme'
import type { TaskAttachment } from '@/lib/types'

const attachmentPath = (storeId: string, taskId: string, attachmentId: string) =>
  `/api/stores/${storeId}/tasks/${taskId}/attachments/${attachmentId}`

/**
 * Zrzuty ekranu przy zadaniu (dodane w przeglądarce, z dorysowanymi strzałkami i napisami): miniatury w poziomym
 * pasku, dotknięcie otwiera zrzut na cały ekran.
 */
export function TaskAttachments({
  storeId,
  taskId,
  attachments,
}: {
  storeId: string
  taskId: string
  attachments: TaskAttachment[]
}) {
  const t = useTheme()
  const [open, setOpen] = useState<TaskAttachment | null>(null)
  if (attachments.length === 0) return null

  return (
    <View style={{ gap: 8 }}>
      <Text style={{ color: t.mutedText, fontSize: 13, fontWeight: '600' }}>Zrzuty ekranu ({attachments.length})</Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 8 }}>
        {attachments.map((attachment, index) => (
          <Pressable
            key={attachment.id}
            onPress={() => setOpen(attachment)}
            accessibilityRole="imagebutton"
            accessibilityLabel={`Zrzut ${index + 1}, otwórz na cały ekran`}
            style={({ pressed }) => [styles.thumb, { borderColor: t.border, opacity: pressed ? 0.7 : 1 }]}
          >
            <Image
              source={imageSource(attachmentPath(storeId, taskId, attachment.id))}
              resizeMode="cover"
              accessibilityIgnoresInvertColors
              style={styles.thumbImage}
            />
          </Pressable>
        ))}
      </ScrollView>
      <Viewer storeId={storeId} taskId={taskId} attachment={open} onClose={() => setOpen(null)} />
    </View>
  )
}

function Viewer({
  storeId,
  taskId,
  attachment,
  onClose,
}: {
  storeId: string
  taskId: string
  attachment: TaskAttachment | null
  onClose: () => void
}) {
  const insets = useSafeAreaInsets()
  const { width, height } = useWindowDimensions()
  return (
    <Modal
      visible={attachment !== null}
      animationType="fade"
      onRequestClose={onClose}
      statusBarTranslucent
      supportedOrientations={['portrait', 'landscape']}
    >
      <View style={[styles.viewer, { paddingTop: insets.top, paddingBottom: insets.bottom }]}>
        {attachment ? (
          // Zrzut z komputera jest szeroki: obrócenie telefonu go powiększa, a przewijanie w obie strony pozwala
          // przyjrzeć się szczegółom.
          <ScrollView
            maximumZoomScale={4}
            minimumZoomScale={1}
            centerContent
            contentContainerStyle={{ flexGrow: 1, justifyContent: 'center' }}
          >
            <Image
              source={imageSource(attachmentPath(storeId, taskId, attachment.id))}
              resizeMode="contain"
              accessibilityIgnoresInvertColors
              accessibilityLabel={attachment.fileName}
              style={{ width, height: height - insets.top - insets.bottom - 64 }}
            />
          </ScrollView>
        ) : null}
        <View style={styles.viewerBar}>
          <Text style={styles.viewerCaption} numberOfLines={1}>
            {attachment?.createdByName ?? ''}
          </Text>
          <Pressable
            onPress={onClose}
            accessibilityRole="button"
            accessibilityLabel="Zamknij"
            hitSlop={8}
            style={({ pressed }) => [styles.close, { opacity: pressed ? 0.7 : 1 }]}
          >
            <Text style={styles.closeText}>Zamknij</Text>
          </Pressable>
        </View>
      </View>
    </Modal>
  )
}

const styles = StyleSheet.create({
  thumb: { borderWidth: StyleSheet.hairlineWidth, borderRadius: 10, overflow: 'hidden' },
  thumbImage: { width: 128, height: 80 },
  viewer: { flex: 1, backgroundColor: '#000' },
  viewerBar: {
    height: 64,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    gap: 12,
  },
  viewerCaption: { color: '#d4d4d8', fontSize: 14, flex: 1 },
  close: { paddingHorizontal: 16, paddingVertical: 10, borderRadius: 999, backgroundColor: '#27272a' },
  closeText: { color: '#fff', fontSize: 15, fontWeight: '600' },
})
