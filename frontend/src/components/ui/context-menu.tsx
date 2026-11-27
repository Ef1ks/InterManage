import { ContextMenu as ContextMenuPrimitive } from '@base-ui/react/context-menu'

import { cn } from '@/lib/utils'

/**
 * Menu pod prawym przyciskiem myszy. Pozycje i separatory są wspólne z `DropdownMenu` (`DropdownMenuItem`,
 * `DropdownMenuSeparator`), bo Base UI dzieli je między oba menu.
 */
function ContextMenu({ ...props }: ContextMenuPrimitive.Root.Props) {
  return <ContextMenuPrimitive.Root data-slot="context-menu" {...props} />
}

function ContextMenuTrigger({ ...props }: ContextMenuPrimitive.Trigger.Props) {
  return <ContextMenuPrimitive.Trigger data-slot="context-menu-trigger" {...props} />
}

function ContextMenuContent({ className, ...props }: ContextMenuPrimitive.Popup.Props) {
  return (
    <ContextMenuPrimitive.Portal>
      <ContextMenuPrimitive.Positioner className="isolate z-50 outline-none">
        <ContextMenuPrimitive.Popup
          data-slot="context-menu-content"
          className={cn(
            'z-50 max-h-(--available-height) w-max max-w-(--available-width) origin-(--transform-origin) overflow-x-hidden overflow-y-auto rounded-xl glass-float p-1 text-popover-foreground duration-100 outline-none data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95 data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95',
            className,
          )}
          {...props}
        />
      </ContextMenuPrimitive.Positioner>
    </ContextMenuPrimitive.Portal>
  )
}

export { ContextMenu, ContextMenuContent, ContextMenuTrigger }
