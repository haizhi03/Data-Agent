import * as React from 'react';
import * as ContextMenuPrimitive from '@radix-ui/react-context-menu';
import { Check, ChevronRight, Circle } from 'lucide-react';
import { cn } from '../../lib/utils';

// Navicat-style submenus: hover briefly to open, leave to close immediately,
// so only the item under the pointer can own a visible submenu.
const SUBMENU_OPEN_DELAY = 200;

let menuIdCounter = 0;

interface MenuSubContextValue {
  subId: string;
  ancestors: string[];
  isWithinSub: (target: EventTarget | null) => boolean;
  onTriggerEnter: () => void;
  onTriggerLeave: (event: React.PointerEvent) => void;
  onTriggerPress: () => void;
  onTriggerClick: () => void;
  onPanelEnter: () => void;
  onPanelLeave: (event: React.PointerEvent) => void;
}

const MenuSubContext = React.createContext<MenuSubContextValue | null>(null);

const ContextMenu = ContextMenuPrimitive.Root;
ContextMenu.displayName = 'ContextMenu';
const ContextMenuTrigger = ContextMenuPrimitive.Trigger;
const ContextMenuGroup = ContextMenuPrimitive.Group;
const ContextMenuPortal = ContextMenuPrimitive.Portal;
const ContextMenuSub = ({
  children,
  onOpenChange,
  ...props
}: React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.Sub>) => {
  const [open, setOpen] = React.useState(false);
  const parentSub = React.useContext(MenuSubContext);
  const idRef = React.useRef<string>();
  if (!idRef.current) idRef.current = `submenu-${++menuIdCounter}`;
  const subId = idRef.current;
  const ancestors = React.useMemo(
    () => (parentSub ? [...parentSub.ancestors, parentSub.subId] : []),
    [parentSub]
  );
  const openTimerRef = React.useRef<number>();
  const pointerOverRef = React.useRef(false);
  const clickIntentRef = React.useRef(false);

  const cancelOpen = React.useCallback(() => {
    if (openTimerRef.current !== undefined) {
      window.clearTimeout(openTimerRef.current);
      openTimerRef.current = undefined;
    }
  }, []);

  const openNow = React.useCallback(() => {
    cancelOpen();
    setOpen(true);
  }, [cancelOpen]);

  const scheduleOpen = React.useCallback(() => {
    if (open || openTimerRef.current !== undefined) return;
    openTimerRef.current = window.setTimeout(() => {
      openTimerRef.current = undefined;
      setOpen(true);
    }, SUBMENU_OPEN_DELAY);
  }, [open]);

  const closeNow = React.useCallback(() => {
    cancelOpen();
    setOpen(false);
  }, [cancelOpen]);

  React.useEffect(() => cancelOpen, [cancelOpen]);

  const isWithinSub = React.useCallback(
    (target: EventTarget | null) =>
      target instanceof Element
      && target.closest(
        `[data-menu-sub="${subId}"], [data-menu-sub-ancestors~="${subId}"]`
      ) !== null,
    [subId]
  );

  const handleOpenChange = React.useCallback(
    (next: boolean) => {
      if (!next) {
        closeNow();
        onOpenChange?.(false);
        return;
      }
      // Radix opens submenus on pointer move; keep desktop-menu behaviour by
      // delaying hover-driven opening, while click and keyboard open instantly.
      if (pointerOverRef.current && !clickIntentRef.current) {
        scheduleOpen();
        return;
      }
      clickIntentRef.current = false;
      openNow();
    },
    [closeNow, openNow, scheduleOpen, onOpenChange]
  );

  const handleTriggerEnter = React.useCallback(() => {
    pointerOverRef.current = true;
    scheduleOpen();
  }, [scheduleOpen]);

  const handleTriggerPress = React.useCallback(() => {
    clickIntentRef.current = true;
  }, []);

  const handleTriggerClick = React.useCallback(() => {
    clickIntentRef.current = false;
    openNow();
  }, [openNow]);

  const handleTriggerLeave = React.useCallback(
    (event: React.PointerEvent) => {
      pointerOverRef.current = false;
      if (isWithinSub(event.relatedTarget)) return;
      closeNow();
    },
    [isWithinSub, closeNow]
  );

  const handlePanelEnter = React.useCallback(() => {
    pointerOverRef.current = true;
  }, []);

  const handlePanelLeave = React.useCallback(
    (event: React.PointerEvent) => {
      pointerOverRef.current = false;
      if (isWithinSub(event.relatedTarget)) return;
      closeNow();
    },
    [isWithinSub, closeNow]
  );

  const contextValue = React.useMemo<MenuSubContextValue>(
    () => ({
      subId,
      ancestors,
      isWithinSub,
      onTriggerEnter: handleTriggerEnter,
      onTriggerLeave: handleTriggerLeave,
      onTriggerPress: handleTriggerPress,
      onTriggerClick: handleTriggerClick,
      onPanelEnter: handlePanelEnter,
      onPanelLeave: handlePanelLeave,
    }),
    [
      subId,
      ancestors,
      isWithinSub,
      handleTriggerEnter,
      handleTriggerLeave,
      handleTriggerPress,
      handleTriggerClick,
      handlePanelEnter,
      handlePanelLeave,
    ]
  );

  return (
    <MenuSubContext.Provider value={contextValue}>
      <ContextMenuPrimitive.Sub {...props} open={open} onOpenChange={handleOpenChange}>
        {children}
      </ContextMenuPrimitive.Sub>
    </MenuSubContext.Provider>
  );
};
ContextMenuSub.displayName = 'ContextMenuSub';
const ContextMenuRadioGroup = ContextMenuPrimitive.RadioGroup;

const ContextMenuSubTrigger = React.forwardRef<
  React.ElementRef<typeof ContextMenuPrimitive.SubTrigger>,
  React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.SubTrigger> & { inset?: boolean }
>(({ className, inset, children, onPointerEnter, onPointerLeave, onPointerDown, onClick, ...props }, ref) => {
  const sub = React.useContext(MenuSubContext);
  return (
    <ContextMenuPrimitive.SubTrigger
      ref={ref}
      data-menu-sub={sub?.subId}
      data-menu-sub-ancestors={sub?.ancestors.join(' ') || undefined}
      onPointerEnter={(event) => {
        sub?.onTriggerEnter();
        onPointerEnter?.(event);
      }}
      onPointerLeave={(event) => {
        sub?.onTriggerLeave(event);
        onPointerLeave?.(event);
      }}
      onPointerDown={(event) => {
        sub?.onTriggerPress();
        onPointerDown?.(event);
      }}
      onClick={(event) => {
        sub?.onTriggerClick();
        onClick?.(event);
      }}
      className={cn(
        'flex cursor-default select-none items-center rounded-sm px-2 py-1.5 text-sm outline-none focus:bg-accent data-[state=open]:bg-accent',
        inset && 'pl-8',
        className
      )}
      {...props}
    >
      {children}
      <ChevronRight className="ml-auto h-4 w-4" />
    </ContextMenuPrimitive.SubTrigger>
  );
});
ContextMenuSubTrigger.displayName = ContextMenuPrimitive.SubTrigger.displayName;

const ContextMenuSubContent = React.forwardRef<
  React.ElementRef<typeof ContextMenuPrimitive.SubContent>,
  React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.SubContent>
>(({ className, onPointerEnter, onPointerLeave, ...props }, ref) => {
  const sub = React.useContext(MenuSubContext);
  return (
    <ContextMenuPrimitive.SubContent
      ref={ref}
      data-menu-sub={sub?.subId}
      data-menu-sub-ancestors={sub?.ancestors.join(' ') || undefined}
      onPointerEnter={(event) => {
        sub?.onPanelEnter();
        onPointerEnter?.(event);
      }}
      onPointerLeave={(event) => {
        sub?.onPanelLeave(event);
        onPointerLeave?.(event);
      }}
      className={cn(
        'z-50 min-w-[8rem] overflow-hidden rounded-md border bg-popover p-1 text-popover-foreground shadow-lg data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2',
        className
      )}
      {...props}
    />
  );
});
ContextMenuSubContent.displayName = ContextMenuPrimitive.SubContent.displayName;

const ContextMenuContent = React.forwardRef<
  React.ElementRef<typeof ContextMenuPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.Content>
>(({ className, ...props }, ref) => (
  <ContextMenuPrimitive.Portal>
    <ContextMenuPrimitive.Content
      ref={ref}
      className={cn(
        'z-50 min-w-[8rem] overflow-hidden rounded-md border bg-popover p-1 text-popover-foreground shadow-md data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2',
        className
      )}
      {...props}
    />
  </ContextMenuPrimitive.Portal>
));
ContextMenuContent.displayName = ContextMenuPrimitive.Content.displayName;

const ContextMenuItem = React.forwardRef<
  React.ElementRef<typeof ContextMenuPrimitive.Item>,
  React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.Item> & { inset?: boolean }
>(({ className, inset, ...props }, ref) => (
  <ContextMenuPrimitive.Item
    ref={ref}
    className={cn(
      'relative flex cursor-default select-none items-center rounded-sm px-2 py-1.5 text-sm outline-none transition-colors focus:bg-accent focus:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
      inset && 'pl-8',
      className
    )}
    {...props}
  />
));
ContextMenuItem.displayName = ContextMenuPrimitive.Item.displayName;

const ContextMenuCheckboxItem = React.forwardRef<
  React.ElementRef<typeof ContextMenuPrimitive.CheckboxItem>,
  React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.CheckboxItem>
>(({ className, children, checked, ...props }, ref) => (
  <ContextMenuPrimitive.CheckboxItem
    ref={ref}
    className={cn(
      'relative flex cursor-default select-none items-center rounded-sm py-1.5 pl-8 pr-2 text-sm outline-none transition-colors focus:bg-accent focus:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
      className
    )}
    checked={checked}
    {...props}
  >
    <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
      <ContextMenuPrimitive.ItemIndicator>
        <Check className="h-4 w-4" />
      </ContextMenuPrimitive.ItemIndicator>
    </span>
    {children}
  </ContextMenuPrimitive.CheckboxItem>
));
ContextMenuCheckboxItem.displayName = ContextMenuPrimitive.CheckboxItem.displayName;

const ContextMenuRadioItem = React.forwardRef<
  React.ElementRef<typeof ContextMenuPrimitive.RadioItem>,
  React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.RadioItem>
>(({ className, children, ...props }, ref) => (
  <ContextMenuPrimitive.RadioItem
    ref={ref}
    className={cn(
      'relative flex cursor-default select-none items-center rounded-sm py-1.5 pl-8 pr-2 text-sm outline-none transition-colors focus:bg-accent focus:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
      className
    )}
    {...props}
  >
    <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
      <ContextMenuPrimitive.ItemIndicator>
        <Circle className="h-2 w-2 fill-current" />
      </ContextMenuPrimitive.ItemIndicator>
    </span>
    {children}
  </ContextMenuPrimitive.RadioItem>
));
ContextMenuRadioItem.displayName = ContextMenuPrimitive.RadioItem.displayName;

const ContextMenuLabel = React.forwardRef<
  React.ElementRef<typeof ContextMenuPrimitive.Label>,
  React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.Label> & { inset?: boolean }
>(({ className, inset, ...props }, ref) => (
  <ContextMenuPrimitive.Label
    ref={ref}
    className={cn('px-2 py-1.5 text-sm font-semibold', inset && 'pl-8', className)}
    {...props}
  />
));
ContextMenuLabel.displayName = ContextMenuPrimitive.Label.displayName;

const ContextMenuSeparator = React.forwardRef<
  React.ElementRef<typeof ContextMenuPrimitive.Separator>,
  React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.Separator>
>(({ className, ...props }, ref) => (
  <ContextMenuPrimitive.Separator
    ref={ref}
    className={cn('-mx-1 my-1 h-px bg-muted', className)}
    {...props}
  />
));
ContextMenuSeparator.displayName = ContextMenuPrimitive.Separator.displayName;

export {
  ContextMenu,
  ContextMenuTrigger,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuCheckboxItem,
  ContextMenuRadioItem,
  ContextMenuLabel,
  ContextMenuSeparator,
  ContextMenuGroup,
  ContextMenuPortal,
  ContextMenuSub,
  ContextMenuSubContent,
  ContextMenuSubTrigger,
  ContextMenuRadioGroup,
};
